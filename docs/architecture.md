# 系统架构说明

> Smart File Processor · 架构文档 v2.0
> 最后更新：2026-07-15

---

## 架构概览

Smart File Processor 采用**经典的单体分层架构**，通过 RabbitMQ 实现上传与处理的**异步解耦**，通过 Elasticsearch 实现**全文检索**，通过 MD5 + 分片上传实现**大文件处理**。

```
┌─────────────────────────────────────────────────────────┐
│                      用户浏览器                          │
│                (Vue 3 + Element Plus)                    │
└─────────────────────┬───────────────────────────────────┘
                      │ HTTP (REST API)
                      ▼
┌─────────────────────────────────────────────────────────┐
│                   Spring Boot 3.2                        │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  GlobalExceptionHandler (@RestControllerAdvice)   │   │
│  │  统一拦截: Multipart/IO/Amqp/参数校验/兜底异常     │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  ┌──────────────┐  ┌───────────────────────────────┐    │
│  │  Controller   │  │          Service              │    │
│  │              │  │                               │    │
│  │ FileController│──▶ FileInfoService (CRUD+分页)   │    │
│  │ ChunkUpload  │──▶ ChunkUploadService            │    │
│  │   Controller │  │   (MD5秒传/分片/合并/MD5校验)   │    │
│  │ SearchCtrl   │  │ OssService (单例客户端)        │    │
│  │              │  │ FileContentExtractor           │    │
│  │              │  │ FileUploadProducer (MQ生产者)  │    │
│  │              │  │ FileUploadConsumer (MQ消费者)  │    │
│  │              │  │ DeadLetterConsumer (死信处理)  │    │
│  │              │  │ OutboxEventService (事件管理)  │    │
│  │              │  │ OutboxSyncScheduler (ES同步)   │    │
│  └──────────────┘  └───────────────┬───────────────┘    │
│                                     │                     │
│  ┌──────────────────────┐          │                     │
│  │  Mapper (MyBatis)     │          │                     │
│  │  FileInfoMapper       │◀─────────┘                     │
│  │  FileChunkMapper      │                               │
│  │  OutboxEventMapper    │                               │
│  └──────────┬───────────┘                                │
└─────────────┼────────────────────────────────────────────┘
              │
    ┌─────────┼─────────────────────────┐
    ▼         ▼                         ▼
┌──────────┐ ┌──────────────────┐ ┌──────────────────┐
│ RabbitMQ │ │    MySQL 8.0     │ │ Elasticsearch 8  │
│  3.13    │ │  file_processor   │ │                  │
│          │ │                  │ │  file_index      │
│ 业务队列  │ │  file_info (17列) │ │  (搜索索引)       │
│ DLX/DLQ  │ │  file_chunk      │ │                  │
│          │ │  outbox_event    │ │                  │
└────┬─────┘ └──────────────────┘ └──────────────────┘
     │
     ▼
┌──────────────────┐
│   阿里云 OSS      │
│  对象存储         │
└──────────────────┘
```

---

## 分层设计

### 1. 表现层（Controller）

| 类 | 职责 | 外部依赖 |
|---|------|----------|
| `FileController` | 文件上传、列表查询（含分页）、详情查询 | FileInfoService, FileUploadProducer, 本地磁盘 |
| `ChunkUploadController` | 分片上传（check/init/upload/merge 四个端点） | ChunkUploadService |
| `SearchController` | 全文搜索（ES multiMatch + MySQL 回查） | ElasticsearchClient, FileInfoService |

- 接收 HTTP 请求，调用 Service，返回统一 `Result<T>` 响应。
- Controller 层不写 try-catch 处理异常，由 `GlobalExceptionHandler` 统一拦截。
- 文件类型判断统一使用 `FileTypeUtil.getFileType()`。
- 分页接口向后兼容：无参数时返回全量数组，带 `page`/`size` 时返回 `PageResult`。

### 2. 业务层（Service）

| 类 | 职责 | 关键逻辑 |
|---|------|----------|
| `FileInfoService` | 文件元数据 CRUD + 分页 | `saveFileInfo()`（简单上传）、`saveChunkedFileInfo()`（分片上传）、`getPage()`（分页查询）、`updateStatus()`（事务更新） |
| `ChunkUploadService` | 分片上传核心逻辑 | MD5 秒传校验、分片初始化/上传/合并、流式合并 + MD5 校验、触发 MQ 异步解析 |
| `OssService` | 阿里云 OSS 上传 | **单例客户端**：@PostConstruct 初始化 OSS Client，@PreDestroy 关闭，所有上传复用同一实例 |
| `FileContentExtractor` | 文本提取 | PDF（PDFBox `Loader.loadPDF`）、Word（POI `XWPFDocument`）、TXT（`FileInputStream` UTF-8） |
| `FileUploadProducer` | MQ 生产者 | `sendUploadTask()` 发送到交换机 `file.upload.exchange`，附带 CorrelationData、deliveryMode=PERSISTENT |
| `FileUploadConsumer` | MQ 消费者 | `@RabbitListener` + MANUAL ACK + Spring Retry（最多 3 次，2s→4s），编排处理链路 |
| `DeadLetterConsumer` | 死信消费者 | 监听 `file.upload.dlq`，提取 x-death 头，记录日志 + 写入 DB `parse_status=PARSE_FAILED` |
| `OutboxEventService` | Outbox 事件管理 | 幂等创建事件、claim 抢占、markSuccess/Failed、指数退避重试 |
| `OutboxSyncScheduler` | 异步 ES 同步 | `@Scheduled(5s)` 扫描 outbox_event → claim → 回查 MySQL → ES upsert/delete |
| `FileTypeUtil` | 文件类型判断 | 静态工具方法，null 安全、无扩展名回退，统一 pdf/word/image/text/other 映射 |

### 3. 持久层（Mapper）

| 接口 | 方式 | 说明 |
|------|------|------|
| `FileInfoMapper` | MyBatis 注解 | CRUD + `findPage`/`count`（分页）、MD5 秒传查询、上传/解析状态更新、分片进度更新、合并信息更新 |
| `FileChunkMapper` | MyBatis 注解 | `insertOrUpdate`（ON DUPLICATE KEY UPDATE 防重）、`findByFileMd5`、`countUploadedChunks` |

### 4. 配置层（Config）

| 类 | 职责 |
|---|------|
| `OssConfig` | `@ConfigurationProperties(prefix="aliyun.oss")` 绑定 OSS 配置 |
| `RabbitMqConfig` | 声明 Exchange/Queue/Binding，配置 RabbitTemplate（JSON 转换器、ConfirmCallback、ReturnsCallback） |

### 5. 异常处理层（Exception）

| 类 | 职责 |
|---|------|
| `GlobalExceptionHandler` | `@RestControllerAdvice` 统一拦截：`MultipartException` / `MaxUploadSizeExceededException`、`MethodArgumentNotValidException`、`IOException`、`AmqpException`、`IllegalArgumentException`、`Exception` 兜底。全部返回 `Result.error()` 格式。 |

### 6. 数据层（Entity + DTO）

| 类 | 类型 | 存储位置 | 说明 |
|---|------|----------|------|
| `FileInfo` | Entity | MySQL | 文件元数据（17 个字段，含 v2 扩展字段） |
| `FileChunk` | Entity | MySQL | 分片记录（9 个字段） |
| `FileDocument` | Entity | Elasticsearch | 搜索文档（5 个字段：id, fileName, content, fileType, ossUrl） |
| `FileUploadMessage` | DTO | RabbitMQ | 队列消息体（fileId, filePath, fileName） |
| `Result<T>` | DTO | HTTP Response | 统一响应 `{code, message, data}` |
| `PageResult<T>` | DTO | HTTP Response | 分页响应 `{list, total, page, size, totalPages}` |

### 7. 解析安全层（FileContentExtractor）

Phase 5 为 `FileContentExtractor` 增加了多层 OOM 保护，防止大文件、恶意文件或损坏文件导致 JVM 崩溃：

| 格式 | 文件大小上限 | 结构上限 | 最终字符上限 | 截断方式 |
|------|------------|---------|-------------|---------|
| TXT | 10 MB | — | 5,000,000 | `BufferedReader` + char buffer，超限立即停止 |
| PDF | 20 MB | 500 页 | 5,000,000 | `BoundedWriter` + `writeText()`，不生成全量 String |
| DOCX | 20 MB | 10,000 段 | 5,000,000 | 遍历段落时累计，超限截断 |

**安全错误消息**：解析失败时返回简短安全消息（如"文件过大"/"文件格式异常"/"解析异常"），完整异常写入 `log.error`。不将底层异常细节暴露给调用方或存入 ES。

---

## 核心数据流

### 上传流（小文件，简单上传）

```
POST /api/file/upload
  │
  ├─ FileController.upload(MultipartFile)
  │   ├─ [1] FileTypeUtil.getFileType(originalName) → 统一判断文件类型
  │   ├─ [2] FileInfoService.saveFileInfo() → INSERT (status=0, uploadStatus=UPLOADED)
  │   ├─ [3] file.transferTo(uploads/{id}_{name}) → 暂存本地磁盘
  │   ├─ [4] FileUploadProducer.sendUploadTask(msg) → RabbitMQ
  │   │       (Producer Confirm + ReturnsCallback + deliveryMode=PERSISTENT)
  │   └─ [5] return Result.success("已提交处理")
  │
  ▼ （异步）
  FileUploadConsumer.handleFileUpload(msg, channel, deliveryTag)
  │
  ├─ [1] updateParseStatus(PARSING) → DB
  ├─ [2] OssService.uploadFile() → 阿里云 OSS（复用单例客户端）
  ├─ [3] FileContentExtractor.extractContent() → 文本内容
  ├─ [4] FileInfoService.updateStatus(id, 2, content, ossUrl) → UPDATE MySQL（事务）
  ├─ [5] elasticsearchClient.index() → ES file_index
  ├─ [6] tempFile.delete() → 清理本地文件
  └─ [7] channel.basicAck(deliveryTag) → 手动确认
       │
       ├─ 成功路径 → basicAck → 消息移除
       ├─ FileNotFoundException → basicNack(requeue=false) → DLQ
       └─ 其他异常 → throw RuntimeException → Spring Retry
            │
            ├─ 重试成功 (≤3次) → basicAck
            └─ 重试耗尽 → basicNack(requeue=false) → DLX → DLQ
                 └─ DeadLetterConsumer → 记录日志 + 写入 PARSE_FAILED
```

### 上传流（大文件，分片上传）

```
POST /api/file/chunk/check      ← MD5 秒传校验
  └─ 命中 → instantUpload=true → 跳过上传，直接复用
     未命中 → instantUpload=false → 继续

POST /api/file/chunk/init       ← 初始化分片任务
  ├─ 检查已上传完成 → 复用
  ├─ 检查进行中任务 → 复用（断点续传）
  └─ 新建 → INSERT file_info (uploadStatus=INIT, isChunked=true)

POST /api/file/chunk/upload     ← 逐片上传（可并发或顺序）
  ├─ 流式写入 {chunkIndex}.part → 8KB 缓冲区，不占内存
  ├─ INSERT ... ON DUPLICATE KEY UPDATE file_chunk → 幂等
  ├─ COUNT uploaded chunks → 以 DB 为准
  └─ 全部上传完成 → updateUploadStatus(READY_TO_MERGE)

POST /api/file/chunk/merge      ← 合并分片
  ├─ 校验 uploadStatus = READY_TO_MERGE
  ├─ 校验分片数量 = totalChunks、索引连续 [0, totalChunks-1]
  ├─ 校验每个分片状态 UPLOADED + .part 文件存在
  ├─ updateUploadStatus(MERGING)
  ├─ 流式合并 .part → 完整文件（8KB 缓冲区）
  ├─ 计算合并文件 MD5（MessageDigest 流式更新）
  ├─ MD5 不匹配 → updateUploadStatus(MERGE_FAILED)，保留 .part 文件
  ├─ MD5 匹配 → 事务更新（uploadStatus=UPLOADED, parseStatus=WAITING_PARSE）
  ├─ sendUploadTask → RabbitMQ → Consumer 异步解析
  └─ 清理 .part 文件和分片目录
```

### 搜索流

```
GET /api/search?keyword=xxx
  │
  └─ SearchController.search(keyword)
      ├─ [1] elasticsearchClient.search(multiMatch: fileName + content)
      ├─ [2] hits.hits() → 提取 FileDocument.id
      ├─ [3] fileInfoService.getById(id) → 逐条回查 MySQL
      └─ [4] return Result.success(List<FileInfo>)
```

### ES 一致性流（Transactional Outbox）

```
Consumer 处理成功
  │
  ├─ [1] FileInfoService.updateStatusWithOutbox()  ← @Transactional
  │       ├─ UPDATE file_info (status=2, content, ossUrl, parseStatus=PARSE_SUCCESS)
  │       └─ INSERT ON DUPLICATE KEY UPDATE outbox_event (status=PENDING)
  │
  ▼ （异步，@Scheduled 5s）
OutboxSyncScheduler.syncToElasticsearch()
  │
  ├─ [1] claimEvents() → 原子抢占 PENDING/可重试 FAILED 事件
  ├─ [2] 回查 MySQL file_info 获取最新数据
  ├─ [3] ES upsert（_id = fileId，幂等）
  │       ├─ 成功 → markSuccess()
  │       └─ 失败 → markFailed()（指数退避，max 5 retries，上限 10min）
  └─ [4] 最终 FAILED 事件等待人工补偿
```

> 详细设计见 [search-consistency.md](./search-consistency.md)

### 文件状态机

当前使用双状态模型：`uploadStatus`（上传维度）+ `parseStatus`（解析维度），正交独立。

```
uploadStatus:                     parseStatus:
  INIT ──→ UPLOADING               NOT_PARSED ──→ WAITING_PARSE
   │           │                       │               │
   │           ▼                       │               ▼
   │      READY_TO_MERGE               │            PARSING
   │           │                       │               │
   │           ▼                       │          ┌────┴────┐
   │        MERGING                    │          ▼         ▼
   │           │                       │    PARSE_SUCCESS  PARSE_FAILED
   │      ┌────┴────┐                  │
   │      ▼         ▼                  │
   │  UPLOADED  MERGE_FAILED           │
   └───────────────────────────────────┘
```

- `uploadStatus=UPLOADED` 表示文件已就绪，可进入解析流程
- `parseStatus=WAITING_PARSE` 由分片合并完成后设置，表示等待 MQ Consumer 处理
- Consumer 处理开始 → `PARSING`，成功 → `PARSE_SUCCESS`，失败 → `PARSE_FAILED`

---

## RabbitMQ 可靠消息链路

```
                   ┌──────────────────────────────────────────┐
                   │  Producer (FileUploadProducer)           │
                   │                                          │
                   │  convertAndSend(                         │
                   │    exchange: file.upload.exchange,        │
                   │    routingKey: file.upload.routing.key,   │
                   │    message: FileUploadMessage (JSON),     │
                   │    postProcessor: deliveryMode=PERSISTENT,│
                   │    correlationData: file-upload:{id}:{uuid}│
                   │  )                                       │
                   │                                          │
                   │  ConfirmCallback → 记录 Broker 已接收     │
                   │  ReturnsCallback → 记录消息无法路由       │
                   └──────────┬───────────────────────────────┘
                              │
                              ▼
                   ┌──────────────────────────────────────────┐
                   │  file.upload.exchange (Direct, durable)   │
                   │       │                                   │
                   │       ▼                                   │
                   │  file.upload.queue (durable, DLX-bound)   │
                   └──────────┬───────────────────────────────┘
                              │
                              ▼
                   ┌──────────────────────────────────────────┐
                   │  Consumer (FileUploadConsumer)            │
                   │  acknowledge-mode: manual                 │
                   │  prefetch: 1                              │
                   │                                          │
                   │  成功 → channel.basicAck(tag)            │
                   │  FileNotFoundException                    │
                   │    → channel.basicNack(tag, requeue=false)│
                   │    → DLX → DLQ → DeadLetterConsumer      │
                   │  其他 Exception                          │
                   │    → throw RuntimeException               │
                   │    → Spring Retry (3次, 2s→4s)           │
                   │    → 全部失败 → basicNack(requeue=false)  │
                   └──────────┬───────────────────────────────┘
                              │ (requeue=false)
                              ▼
                   ┌──────────────────────────────────────────┐
                   │  file.upload.dlx.exchange (Direct)        │
                   │       │                                   │
                   │       ▼                                   │
                   │  file.upload.dlq (durable)                │
                   │       │                                   │
                   │       ▼                                   │
                   │  DeadLetterConsumer                       │
                   │  → 读取 x-death header                    │
                   │  → 写入 DB parse_status=PARSE_FAILED      │
                   │  → channel.basicAck(tag)                  │
                   └──────────────────────────────────────────┘
```

> 详细工程复盘见 [rabbitmq-reliability-review.md](./rabbitmq-reliability-review.md)

---

## 技术选型依据

| 选型 | 方案 | 理由 |
|------|------|------|
| 框架 | Spring Boot 3.2 | Java 生态最成熟的企业级框架，生态丰富，面试认知度高 |
| ORM | MyBatis | 比 JPA 更灵活，手写 SQL 对理解数据库更友好，国内企业主流 |
| 消息队列 | RabbitMQ | 比 Kafka 轻量，适合任务队列场景；自带管理界面；支持 DLX/DLQ/Confirm 全套可靠机制 |
| 搜索引擎 | Elasticsearch | 全文检索的事实标准，RESTful API 友好 |
| 对象存储 | 阿里云 OSS | 国内云市场份额第一，SDK 成熟，线程安全可单例复用 |
| 文件解析 | PDFBox + POI | Apache 官方库，无需 License，比 Tika 更轻量 |
| 前端 | Vue 3 CDN | 零构建成本，专注展示后端能力 |
| 构建 | Gradle 8.5 | Spring 官方推荐，比 Maven 更简洁灵活 |
| 测试 | JUnit 5 + Mockito + MockMvc | 55 个单元测试，覆盖文件上传、分片上传、全局异常处理、RabbitMQ 可靠消息、Transactional Outbox、文件内容解析等核心链路，standalone 模式避免加载持久层依赖 |

---

## 当前局限与改进方向

| 问题 | 影响 | 改进计划 |
|------|------|----------|
| ~~ES 手动双写，无事务保证~~ | ~~MySQL/ES 数据不一致风险~~ | ✅ Phase 4 已解决（Transactional Outbox） |
| 搜索 N+1 查询（ES → MySQL 逐条回查） | 搜索结果多时性能差 | 后续版本计划批量查询优化 |
| PDF 全量加载到内存 | 超大 PDF 可能 OOM | ✅ Phase 5 已通过门禁+截断解决 |
| 无前端分片上传 UI | 大文件只能用 curl 测试 | 低优先级，后端接口已就绪 |
| 无用户认证 | 不适合直接生产部署 | 🔜 后续规划（Backlog）：Spring Security + JWT 用户认证与权限管理 |

---

## 部署架构

```
┌──────────────────────────────────────────────┐
│               服务器 / 云主机                  │
│                                               │
│  ┌──────────────────┐  ┌──────────────────┐   │
│  │  Spring Boot App │  │  前端静态资源     │   │
│  │  port: 8080      │  │  /index.html     │   │
│  └────────┬─────────┘  └──────────────────┘   │
│           │                                    │
│  ┌────────┴──────────────────────────────┐    │
│  │    Docker Compose (docker compose up)  │    │
│  │                                        │    │
│  │  MySQL:3306    RabbitMQ:5672/15672     │    │
│  │  ES:9200       (healthcheck 自动监控)   │    │
│  └────────────────────────────────────────┘    │
│                                                │
│  ┌────────────────────────────────────────┐    │
│  │         阿里云 OSS（云端服务）           │    │
│  └────────────────────────────────────────┘    │
└──────────────────────────────────────────────┘
```

所有中间件通过 `docker compose up -d` 一键启动，`.env.example` 管理配置。

---

## 未来规划（Future / Planned）

以下能力已设计但未在当前代码中实现：

| 能力 | 设计文档 | 状态 |
|------|------|:--:|
| **Transactional Outbox MySQL → ES 数据同步** | [search-consistency.md](./search-consistency.md) | ✅ 已完成 |
| **解析内存风险控制** | [performance-report.md](./performance-report.md) | ✅ 代码完成，性能数据待实测 |
| **AI 文档问答（RAG）** | [refactor-plan.md](./refactor-plan.md) | 💡 Backlog |
| **SSE 流式响应** | — | 💡 Backlog |
| **LLM 接入** | — | 💡 Backlog |
| **Embedding + Milvus 向量库** | — | 💡 Backlog |

> ⚠️ 架构图中不包含 RAG/AI 链路，该部分仅存在于 Backlog 规划中。
