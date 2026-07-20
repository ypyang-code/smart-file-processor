# 项目面试讲解要点

> Smart File Processor · 面试准备文档
> 最后更新：2026-07-15

本文档用于准备面试中关于本项目的讲解。建议按 **"一句话定位 → 技术栈 → 架构 → 亮点 → 难点 → 成长"** 的逻辑展开。

---

## 1. 30 秒电梯演讲

> "Smart File Processor 是我独立开发的企业级文件处理与检索系统。它支持小文件直传和大文件分片上传（MD5 秒传、断点续传），通过 RabbitMQ 的 MANUAL ACK + DLX/DLQ + Producer Confirm 三层保障实现可靠异步处理，通过 Transactional Outbox Pattern 解决 MySQL 和 Elasticsearch 的最终一致性问题。用了 Spring Boot + MyBatis + RabbitMQ + Elasticsearch + 阿里云 OSS 这套技术栈，写了 35 个单元测试，有完整的 Docker Compose 一键部署方案和技术文档。"

**关键点**：
- "独立开发" → 体现自主性
- "三层保障" → 体现可靠性思考深度
- 一口气说出 5 个中间件 → 体现广度
- "55 个单元测试" → 体现质量意识
- "Docker Compose 一键部署" → 体现工程化能力

---

## 2. 架构讲解（3 分钟版）

### 话术框架

1. **总览**："系统采用经典的单体分层架构，Controller → Service → Mapper 三层。我在标准三层基础上增加了全局异常处理层（@RestControllerAdvice）和工具层（如 FileTypeUtil），确保代码不重复、异常处理统一。"
2. **上传流**："用户上传文件后，Controller 先把文件暂存本地，写一条 MySQL 记录，然后发一条消息到 RabbitMQ 就立即返回。后台的 Consumer 消费消息后，依次完成 OSS 上传、文本提取、更新 MySQL、索引 ES、清理临时文件。整个过程用户只需要等 100ms 左右。"
3. **大文件上传流**："对于大文件，前端先调用 MD5 秒传校验，文件已存在则直接跳过。不存在时先初始化分片任务，然后逐片上传。每片流式写入磁盘，通过 DB 的 ON DUPLICATE KEY UPDATE 支持分片重传。全部分片上传完成后触发合并，服务端流式合并所有分片并计算 MD5 校验值，和客户端上报的 MD5 对比，一致才标记上传完成并触发异步解析。"
4. **可靠性**："RabbitMQ 链路上我做了三层保障：第一层 Producer 发送时做 Confirm + ReturnsCallback + 持久化，第二层 Consumer 做 MANUAL ACK + Spring Retry（3 次指数退避），第三层重试全部失败后进入 DLX/DLQ，由 DeadLetterConsumer 记录 DB 失败原因。"
5. **为什么这样设计**："上传和处理解耦是核心设计思想。如果同步处理，用户上传大文件要等 OSS 上传 + 文本提取 + ES 索引全部完成，体验很差。用 RabbitMQ 解耦后，上传接口很快返回，后台慢慢处理。"

### 预期追问

| 追问 | 应答要点 |
|------|----------|
| "为什么用 RabbitMQ 而不是 Kafka？" | RabbitMQ 更适合任务队列场景（每个消息需要被精确消费一次），自带管理界面，支持 DLX/DLQ/Confirm，学习曲线平缓。Kafka 更偏日志/流式数据、高吞吐场景。这个项目是文档处理任务，RabbitMQ 更合适。 |
| "RabbitMQ 消息丢了怎么办？" | 不会丢。我做了三层保障：Producer Confirm 确保消息到达 Broker，MANUAL ACK 确保 Consumer 处理成功才确认，DLX/DLQ 确保重试耗尽的消息有兜底记录。消息本身也设置了 PERSISTENT 持久化 + durable exchange/queue。 |
| "MySQL 和 ES 数据不一致怎么办？" | Phase 4 已通过 Transactional Outbox Pattern 解决。Consumer 处理完成后，同事务写入 outbox_event + 更新 file_info。定时任务异步扫描 outbox_event 同步 ES，失败自动重试（指数退避最多 5 次）。ES 以 fileId 为 _id 做幂等 upsert。方案选型上，同步双写无分布式事务保证，Canal 需要额外运维成本，Transactional Outbox 仅依赖 MySQL 本地事务，是最轻量的方案。详见 search-consistency.md。 |

---

## 3. 技术亮点展开（5 分钟版）

### 3.1 RabbitMQ 可靠消息（3 层保障）

- **问题**：默认 AUTO ACK 模式下 Consumer 崩溃消息会丢失；Broker 重启未持久化的消息会丢失；重试耗尽的消息会被直接丢弃。
- **方案**：3 层递进保障
- **实现**：
  - **第 1 层（Producer）**：`publisher-confirm-type: correlated` + `mandatory: true` + ReturnsCallback + `deliveryMode=PERSISTENT` + durable exchange/queue
  - **第 2 层（Consumer）**：`acknowledge-mode: manual`，成功 → `basicAck`，文件不存在 → `basicNack(requeue=false)` → DLQ，其他异常 → throw → Spring Retry（3 次，2s→4s 指数退避）
  - **第 3 层（死信）**：重试耗尽 → DLX → DLQ → DeadLetterConsumer 记录日志 + 写入 DB `parse_status=PARSE_FAILED`
- **工程复盘**：见 [rabbitmq-reliability-review.md](./rabbitmq-reliability-review.md)，记录了 7 种故障分支和处理策略。

### 3.2 大文件分片上传

- **问题**：单次上传大文件（500MB+）容易超时、OOM、无法断点续传
- **方案**：MD5 秒传 + 分片流式上传 + 流式合并 + MD5 完整性校验
- **实现**：
  - **MD5 秒传**：fileMd5 + fileSize 联合查询，命中则 instantUpload=true
  - **分片上传**：每片 8KB 缓冲区流式写入 .part 文件，不占内存；`file_chunk` 表用 `ON DUPLICATE KEY UPDATE` 保证分片重传幂等
  - **分片合并**：全部分片到位后流式合并（8KB 缓冲区），`MessageDigest` 流式计算合并文件 MD5，与客户端上报值对比
  - **状态管理**：`uploadStatus`（INIT→UPLOADING→READY_TO_MERGE→MERGING→UPLOADED/MERGE_FAILED）和 `parseStatus`（NOT_PARSED→WAITING_PARSE→PARSING→PARSE_SUCCESS/PARSE_FAILED）双状态正交
- **为什么这样做 MD5 校验**：客户端 MD5 可能被伪造或计算错误，服务端重新计算合并结果保证完整性。

### 3.3 代码质量与可测试性

- **全局异常处理**：`@RestControllerAdvice` 统一拦截 Controller 层异常，返回统一 `Result` 格式。覆盖 Multipart/IO/Amqp/参数校验/兜底异常共 6 种类型。
- **FileTypeUtil 去重**：原来 3 个类中各有自己的 `getFileType()` 方法，且 FileController 和 ChunkUploadService 的处理逻辑一致但 Consumer 少一个 image 分支。抽取为静态工具类，统一处理 null 安全、无扩展名回退。
- **OSS 客户端单例**：原来每次 `uploadFile()` 都 `new OSSClientBuilder().build()` + `finally { shutdown() }`。改为 `@PostConstruct` 初始化、`@PreDestroy` 销毁，全生命周期复用（阿里云 OSS SDK 线程安全）。
- **分页向后兼容**：`GET /api/file/list` 无参数时返回全量数组（旧前端兼容），带 `page`/`size` 时返回 `PageResult` 分页结构。边界保护：page<1→修正为1，size>100→截断为100。
- **55 个测试**：FileControllerTest（8）+ ChunkUploadServiceTest（14）+ OutboxEventServiceTest（12）+ FileContentExtractorTest（20）+ ApplicationTest（1），使用 MockMvc standalone 模式和 Mockito，测试不依赖真实中间件。

### 3.4 多格式文件解析

- **PDF**：Apache PDFBox 3.0.1 `Loader.loadPDF()` + `PDFTextStripper`
- **Word**：Apache POI `XWPFDocument` 逐段落读取
- **TXT**：`FileInputStream` 流式读取 UTF-8
- **扩展性**：`FileContentExtractor.extractContent()` 按文件类型分发，新增格式只需加一个 case

### 3.5 Elasticsearch 全文搜索

- **索引策略**：`multiMatch` 同时搜索 `fileName` 和 `content` 字段
- **查询链路**：ES 返回匹配的 fileId → 回查 MySQL 获取完整 FileInfo
- **为什么回查 MySQL**：ES 中只存搜索用的字段，完整元数据在 MySQL 中，保证单一数据源
- **已知不足**：当前 N+1 查询（每个 ES 命中逐条回查 MySQL），Phase 4 Canal 改造后一并优化为批量查询

---

## 4. 常见追问与应答

### 技术深度类

| 问题 | 建议应答 |
|------|----------|
| "MyBatis 和 JPA 你选哪个？为什么？" | 我选 MyBatis。手写 SQL 对理解数据库更友好，复杂查询更灵活。这个项目用纯注解方式，不需要 XML，保持了代码整洁。`@Param` 绑定参数，`@Options(useGeneratedKeys=true)` 回填自增 ID。 |
| "分页是怎么实现的？" | Mapper 层用 `LIMIT #{offset}, #{size}` + `SELECT COUNT(*)`。Service 层封装为 `PageResult<T>`（list/total/page/size/totalPages）。Controller 层做了向后兼容：无参数时返回全量数组，带参数时返回 PageResult。 |
| "OSS 客户端为什么单例？" | 原来每次上传都创建新客户端然后 shutdown，频繁创建 TCP 连接浪费资源。阿里云 OSS SDK 的客户端是线程安全的，所以改为 @PostConstruct 初始化和 @PreDestroy 销毁，应用生命周期内复用同一个实例。 |
| "ES 索引怎么设计的？" | 单索引 `file_index`，mapping 定义了 `id(long)`, `fileName(text)`, `content(text)`, `fileType(keyword)`, `ossUrl(keyword)`。text 类型用于全文搜索，keyword 类型用于精确匹配。 |
| "如果 ES 挂了，搜索怎么办？" | 目前搜索直接报错，由 GlobalExceptionHandler 兜底。生产环境应该加降级策略：捕获 ES 异常后 fallback 到 MySQL LIKE 查询。 |
| "大文件解析不会 OOM 吗？" | Phase 5 已做多层保护：TXT 用 BufferedReader 流式读取 + 字符数截断（5M 字符）；PDF 做文件大小门禁（20MB）+ 页数门禁（500 页）+ BoundedWriter 截断；DOCX 做文件大小门禁（20MB）+ 段落数门禁（10000 段）+ 字符截断。所有解析失败返回安全消息，完整异常写入日志。PDFBox 不支持流式解析，所以靠门禁而非超时控制。后续可优化为 PDF 流式解析或独立 error_reason 字段。|

### 系统设计类

| 问题 | 建议应答 |
|------|----------|
| "为什么做 MD5 秒传？" | 两个原因：一是避免用户重复上传同一文件浪费带宽和存储；二是分片上传的合并阶段需要对完整性做最终验证。MD5 碰撞概率极低（2^128 分之一），配合 fileSize 双重匹配更安全。 |
| "为什么做分片上传？" | 大文件（100MB+）单次 HTTP 上传容易超时和失败，且失败后需要全部重传。分片上传每片独立传输，失败只重传该片。各分片可以并发上传提高速度。 |
| "为什么引入 RabbitMQ？" | 核心目的是上传和处理解耦。同步处理时用户要等 OSS 上传 + 文本提取 + ES 索引全完成，大文件体验很差。异步处理后上传接口 100ms 内返回，后台慢慢处理。 |
| "如何保证消息可靠？" | 三层保障：Producer Confirm + 持久化 → MANUAL ACK + Spring Retry → DLX/DLQ。消息从发送到确认的每个环节都有失败处理机制。具体见 rabbitmq-reliability-review.md。 |
| "MySQL 和 ES 一致性怎么解决？" | 当前是手动双写，风险在于：ES 写入失败时 Consumer 抛异常触发重试，但如果异常是 ES 返回成功后网络中断（实际写入成功但客户端以为失败），重试会导致 ES 中重复文档。Phase 4 计划用 Canal 监听 binlog 实现最终一致性——MySQL 作为单一写入源，ES 通过 binlog 异步同步。 |
| "为什么做全局异常处理？" | Controller 中到处 try-catch 返回 Result.error() 是重复代码，且容易遗漏异常类型。@RestControllerAdvice 统一拦截后，Controller 代码更干净，异常处理更完整（比如之前没有专门处理 MultipartException 和 AmqpException）。 |
| "为什么做测试覆盖？" | 项目要用于求职展示，如果面试官看到只有一个 contextLoads() 测试，会觉得质量意识不够。我写了 23 个测试覆盖 Controller 和核心 Service，使用 Mockito mock 中间件依赖，不依赖真实 DB/MQ/ES 就能跑。 |

### 自我认知类

| 问题 | 建议应答 |
|------|----------|
| "这个项目最大的不足是什么？" | 一是搜索存在 N+1 查询问题（Phase 5 做批量优化）；二是 ES 全量重建索引接口还未实现；三是没有性能压测数据（Phase 5 计划）。 |
| "如果重新设计，你会改什么？" | 第一，一开始就为 ES 同步设计 Transactional Outbox 而不是手动双写；第二，提前引入 Flyway 做数据库版本管理（现在用纯 SQL 脚本）；第三，前端拆为独立项目用 Vite 构建。 |
| "你在项目中学到了什么？" | 最大的收获是"先跑通再优化"的工程思维。第一个版本只用了 3 天就实现全链路，然后我梳理改进点按 Phase 0→1→2→3→4→5 演进。另一个收获是**文档驱动开发**——我写了架构设计、RabbitMQ 可靠性复盘、Canal 同步设计等 10+ 篇文档，写文档的过程帮我发现了代码里很多之前没注意到的问题。 |

---

## 5. 简历中的项目描述建议

### 项目名称
**Smart File Processor — 企业级文件处理与检索系统**

### 一句话简介
独立设计并实现的企业级文档处理系统，支持小文件上传和大文件分片上传（MD5 秒传/断点续传），基于 RabbitMQ 三层可靠消息链路实现异步处理，集成 Elasticsearch 全文检索，正在推进 Canal 数据一致性同步。

### 技术栈标签
`Java 17` `Spring Boot 3.2` `MyBatis` `RabbitMQ` `Elasticsearch 8` `阿里云 OSS` `MySQL 8` `PDFBox` `Apache POI` `JUnit 5` `Mockito` `Docker Compose`

### 核心职责与成果
- 设计并实现文件上传 → 异步处理 → 云存储 → 内容提取 → 全文搜索的全链路功能
- 实现 RabbitMQ 三层可靠消息保障：MANUAL ACK + DLX/DLQ + Producer Confirm + Spring Retry
- 实现大文件分片上传方案：MD5 秒传、流式分片上传、流式合并 + MD5 完整性校验、双状态机管理
- OSS 客户端单例复用（@PostConstruct/@PreDestroy 生命周期管理）
- 全局异常处理（@RestControllerAdvice）统一拦截 6 种异常类型
- 向后兼容的分页 API 设计（无参数返回数组，带参数返回 PageResult）
- Docker Compose 一键部署中间件 + .env.example 配置管理
- 编写 55 个单元测试（JUnit 5 + Mockito + MockMvc standalone + 真实 PDF/POI 库）
- 撰写 12+ 篇技术文档（架构设计、RabbitMQ 可靠性复盘、ES 一致性设计、性能报告模板、面试准备等）

---

## 6. 关键词速查

面试中如果被问到以下关键词，可以直接关联本项目：

| 关键词 | 项目对应点 |
|--------|-----------|
| 异步处理 | RabbitMQ 解耦上传与处理 |
| 可靠消息 | MANUAL ACK + DLX/DLQ + Producer Confirm + 持久化 + Spring Retry |
| 分片上传 | MD5 秒传 + 流式分片 + 断点续传 + 流式合并 + MD5 校验 |
| 全文检索 | ES multiMatch + 回查 MySQL |
| 对象存储 | 阿里云 OSS，客户端单例复用 |
| 全局异常处理 | @RestControllerAdvice，统一 Result 响应 |
| 向后兼容 | 分页 API 无参数时返回原数组结构 |
| 代码去重 | FileTypeUtil 统一 3 处重复 getFileType |
| 分层架构 | Controller → Service → Mapper + Exception + Utils |
| 单元测试 | JUnit 5 + Mockito + MockMvc standalone |
| RESTful API | 统一 Result 响应体，资源化 URL |
| 工程化 | Docker Compose + .env.example + CHANGELOG |
| 技术选型 | 5 个中间件的选型理由（见 architecture.md） |
| 最终一致性 | Transactional Outbox Pattern，MySQL 事务 + 异步 ES 同步 |
| 演进路线 | 6 Phase roadmap，渐进式改进 |
| 文档驱动 | 10+ 篇技术文档 |
