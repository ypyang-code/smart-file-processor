# Smart File Processor — 现状分析与分阶段改造方案

> **文档版本**：v1.0
> **创建日期**：2026-07-14
> **作者**：杨昀璞
> **适用范围**：Smart File Processor 项目从初始版到企业级展示项目的完整改造路线

---

## 目录

1. [项目当前现状](#一项目当前现状)
2. [距离目标缺失模块](#二距离目标缺失模块)
3. [分阶段改造方案](#三分阶段改造方案)
   - [Phase 1：可靠消息与任务状态机](#phase-1可靠消息与任务状态机)
   - [Phase 2：大文件上传工程化](#phase-2大文件上传工程化)
   - [Phase 3：搜索数据一致性](#phase-3搜索数据一致性--canal-同步)
   - [Phase 4：JVM 优化与压测](#phase-4jvm-优化与压测)
   - [Phase 5：AI / RAG 扩展](#phase-5ai--rag-扩展)
   - [Phase 6：GitHub 展示与文档工程](#phase-6github-展示与文档工程)
4. [Git 分支规划与提交粒度](#四git-分支规划与提交粒度)
5. [README 展示结构建议](#五readme-展示结构建议)
6. [总结](#六总结)

---

## 一、项目当前现状

### 1.1 技术栈概览

| 维度 | 现状 |
|------|------|
| **构建工具** | Gradle 8.5 (Groovy DSL) |
| **Spring Boot** | 3.2.8 |
| **Java** | 17 |
| **ORM** | MyBatis 3.0.3（纯注解，无 XML mapper） |
| **数据库** | MySQL 8.0+（手动建库 `file_processor`，单表 `file_info`） |
| **消息队列** | RabbitMQ（单队列 `file.upload.queue`，无死信队列、无手动 ACK、无生产者 Confirm） |
| **搜索引擎** | Elasticsearch 8.12（`file_index` 索引手动创建，消费者中手动双写同步） |
| **对象存储** | 阿里云 OSS（每次上传新建 OSS 客户端） |
| **文件解析** | PDFBox 3.0.1 / POI 5.2.5 / 原生 InputStream |
| **前端** | 单文件 `index.html`（Vue 3 + Element Plus CDN 方式加载） |
| **测试** | 仅 1 个 Spring Boot 默认冒烟测试 `contextLoads()` |

### 1.2 包结构与职责

```
com.yang.fileprocessor
├── FileProcessorApplication.java    ← 主启动类，@MapperScan + @EnableConfigurationProperties
├── config/
│   ├── OssConfig.java               ← 阿里云 OSS 配置（endpoint, ak, sk, bucket）
│   └── RabbitMqConfig.java          ← RabbitMQ 配置（单队列声明, Jackson 转换器, RabbitTemplate）
├── controller/
│   ├── FileController.java          ← /api/file/upload | /list | /{id}
│   └── SearchController.java        ← /api/search?keyword=xxx
├── dto/
│   ├── FileUploadMessage.java       ← MQ 消息体（fileId, filePath, fileName）
│   └── Result.java                  ← 统一响应体 {code, message, data}
├── entity/
│   ├── FileInfo.java                ← MySQL 实体（9 个字段）
│   └── FileDocument.java            ← ES 文档实体（5 个字段）
├── mapper/
│   └── FileInfoMapper.java          ← MyBatis 注解式 CRUD（insert, findById, findAll, update）
└── service/
    ├── FileContentExtractor.java    ← PDF/Word/TXT 内容提取
    ├── FileInfoService.java         ← 文件信息 CRUD 封装
    ├── FileUploadConsumer.java      ← MQ 消费者：OSS上传→提取文本→更新DB→索引ES→删临时文件
    ├── FileUploadProducer.java      ← MQ 生产者：发送文件处理任务
    └── OssService.java              ← OSS 对象上传
```

**总计 14 个 Java 源文件，6 个包，单模块项目。**

### 1.3 数据库表 `file_info`（推断）

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK AUTO_INC | 主键 |
| `file_name` | VARCHAR | 原始文件名 |
| `file_type` | VARCHAR | pdf / word / text / image / other |
| `file_size` | BIGINT | 文件字节数 |
| `oss_url` | VARCHAR | OSS 公网 URL（nullable） |
| `content` | TEXT | 提取的文字内容（nullable） |
| `status` | INT | 0=待处理, 1=处理中, 2=已完成, 3=失败 |
| `create_time` | DATETIME | 创建时间 |
| `update_time` | DATETIME | 更新时间 |

注：项目中没有 SQL 迁移文件，表需手动创建。

### 1.4 当前数据流

```
用户上传文件
  → FileController.upload()
    ├── 保存 FileInfo 到 MySQL（status=0）
    ├── 保存到本地临时目录 uploads/
    ├── 发送 FileUploadMessage 到 RabbitMQ
    └── 立即返回 "已提交处理"
  → FileUploadConsumer.handleFileUpload()（@RabbitListener）
    ├── 上传文件到 OSS
    ├── 提取文本内容（PDF/Word/TXT）
    ├── 更新 MySQL（status=2, content, ossUrl）
    ├── 索引到 ES（file_index）
    └── 删除本地临时文件

用户搜索
  → SearchController.search(keyword)
    ├── 查询 ES（multiMatch: fileName + content）
    ├── 用 ES 返回的 id 回查 MySQL
    └── 返回 FileInfo 列表
```

### 1.5 现有缺陷汇总

| 类别 | 问题 | 严重程度 |
|------|------|----------|
| **可靠性** | RabbitMQ 无生产者 Confirm、无消费者手动 ACK、无死信队列、无重试策略 | 高 |
| **可靠性** | 消费者异常统一 catch 后设 status=3，不区分可重试/不可重试异常 | 高 |
| **上传** | 仅支持 ≤50MB 单文件上传，无分片、无断点续传、无秒传 | 高 |
| **上传** | 文件完全写入本地磁盘后再处理，大文件磁盘 I/O 瓶颈 | 中 |
| **数据一致性** | ES 同步在消费者中手动双写，与 DB 无事务保证，无对账机制 | 高 |
| **数据一致性** | ES 索引手动创建，无 mapping 版本管理 | 中 |
| **性能** | PDF 解析使用 `Loader.loadPDF(File)` 全量加载，大文件可能 OOM | 高 |
| **性能** | OssService 每次上传新建 OSS 客户端（应复用） | 中 |
| **性能** | 无任何压测数据或 JVM 调优记录 | 中 |
| **搜索** | ES 搜索结果回查 MySQL 逐条查询（N+1 问题） | 中 |
| **代码质量** | `getFileType()` 方法在 Controller 和 Consumer 中重复定义 | 低 |
| **代码质量** | 无全局异常处理器 | 低 |
| **代码质量** | 无接口文档（Swagger/OpenAPI） | 低 |
| **代码质量** | 无输入校验（文件名、文件大小、关键字） | 中 |
| **测试** | 仅 1 个空冒烟测试，无单元测试或集成测试 | 高 |
| **运维** | 无 Dockerfile / docker-compose.yml | 中 |
| **运维** | 敏感信息通过环境变量传递但无 .env.example 模板 | 低 |

---

## 二、距离目标缺失模块

最终定位：**企业级智能文档处理与知识库问答平台**

| 目标能力 | 已有程度 | 缺失项 |
|----------|---------|--------|
| **大文件上传工程化** | 仅基础单文件上传（≤50MB，同步本地写盘） | MD5 秒传、分片上传、断点续传、分片合并、分片记录表、OSS 分片上传 API |
| **异步任务可靠消息** | 基础 Queue + 自动 ACK（默认配置） | 生产者 Confirm、消费者手动 ACK、死信队列（DLX/DLQ）、重试策略（指数退避）、任务状态机、补偿任务 |
| **搜索与数据一致性** | 手动双写 MySQL + ES（消费者中 hardcode） | Canal binlog 监听、ES 同步服务抽象、数据对账、全量重建索引 |
| **JVM 与性能优化** | 无 | 流式解析大文件（InputStream 逐块读）、JMeter 压测脚本、JVM 参数调优、GC 日志分析、压测报告 |
| **AI / RAG 扩展** | 无 | 文本切片（chunking）、Embedding（预留）、向量库 Milvus（预留）、QA 接口、SSE 流式响应、Reranker（预留） |
| **GitHub 展示** | 基础 README（中文，约 150 行） | 架构图、接口文档、Roadmap、压测报告、技术博客链接、项目截图、CI Badge |

---

## 三、分阶段改造方案

### 设计原则

1. **不大规模重写**：优先扩展现有类，保持目录结构和代码风格不变。
2. **每阶段可运行**：每个 Phase 结束时项目可编译 + 可启动 + 核心功能正常。
3. **渐进式引入中间件**：Phase 1→2 不新增中间件，Phase 3 引入 Canal，Phase 5 引入向量库。
4. **改动粒度到文件级**：明确每个文件是"新建/重写/扩展/不改"。

---

### Phase 1：可靠消息与任务状态机

**目标**：让现有 RabbitMQ 链路达到生产级可靠性，建立规范的任务状态流转。

**预计工时**：3-5 天

#### 改动清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `config/RabbitMqConfig.java` | **重写** | 新增 DLX/DLQ 声明（`file.upload.dlx` / `file.upload.dlq`）；`RabbitTemplate` 开启 publisher-confirm 并注册 ConfirmCallback；`SimpleRabbitListenerContainerFactory` 配置手动 ACK 模式 + `prefetch=1` |
| `service/FileUploadProducer.java` | **增强** | `sendUploadTask()` 发送后处理 ConfirmCallback 结果；发送失败（ack=false）将消息写入 DB 补偿表 `task_retry_log` |
| `service/FileUploadConsumer.java` | **增强** | `handleFileUpload()` 增加 `Channel` 参数，成功后调用 `channel.basicAck()`；异常分为 `RetryableException`（Nack 回队列）和 `FatalException`（Nack 不进队列 → DLQ）；保留现有 OSS→提取→DB→ES 逻辑不变 |
| `dto/FileUploadMessage.java` | **扩展** | 新增 `retryCount`（已重试次数，默认 0） |
| `entity/FileInfo.java` | **不改** | status 字段语义复用：0=待处理, 1=处理中, 2=已完成, 3=失败（DLQ）, 4=永久失败 |
| `service/FileInfoService.java` | **增强** | `updateStatus()` 增加状态机校验：`0→1→2` 或 `0→1→3`，拒绝非法跳转并打印 WARN 日志 |

#### 新增文件

| 文件 | 包路径 | 说明 |
|------|--------|------|
| `config/MqRetryConfig.java` | `config` | RabbitMQ 重试策略配置类（`RetryOperationsInterceptor`，指数退避 max-attempts=3） |
| `entity/TaskRetryLog.java` | `entity` | 补偿任务日志实体（fileId, retryCount, maxRetry, lastError, nextRetryTime, status） |
| `mapper/TaskRetryLogMapper.java` | `mapper` | 补偿日志 Mapper（insert, findByStatus, updateStatus） |
| `service/TaskCompensationService.java` | `service` | `@Scheduled` 定时任务（每 30s 扫描 `task_retry_log` 中 PENDING 记录，重新投递到 MQ） |
| `exception/RetryableException.java` | `exception` | 可重试异常（OSS 上传超时、网络抖动等） |
| `exception/FatalException.java` | `exception` | 不可重试异常（文件格式损坏、OSS 认证失败等） |

#### 新增表

```sql
CREATE TABLE task_retry_log (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    file_id      BIGINT       NOT NULL,
    retry_count  INT          DEFAULT 0,
    max_retry    INT          DEFAULT 3,
    last_error   TEXT,
    next_retry_time DATETIME,
    status       VARCHAR(20)  DEFAULT 'PENDING',  -- PENDING / SUCCESS / DEAD
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status_time (status, next_retry_time)
);
```

#### 新增接口

本阶段无新增对外接口。Consumer 异常处理逻辑改变，但对外 API 无变化。

#### 涉及配置变更

`application.yml` 新增：

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated       # 开启生产者确认
    publisher-returns: true                   # 开启路由失败回调
    listener:
      simple:
        acknowledge-mode: manual              # 手动 ACK
        prefetch: 1                           # 每次只取一条消息
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 5000              # 首次重试间隔 5s
          multiplier: 2.0                     # 指数退避（5s→10s→20s）
          max-interval: 60000                 # 最大间隔 60s
```

#### RabbitMQ 手动操作

部署前需要先在 RabbitMQ 管理后台或通过配置创建：
- 死信交换机：`file.upload.dlx`（direct 类型）
- 死信队列：`file.upload.dlq`（绑定 routingKey = `file.upload.dead`）

#### 风险点

- **消息堆积**：手动 ACK + prefetch=1 模式下，消费速度下降，高峰期可能堆积。对策：监控队列深度，必要时增大 consumer 并发数。
- **状态机兼容**：历史数据中有大量 status=3（旧版失败），Phase 1 后将不再直接产生 status=3，而是先进 DLQ。旧 status=3 数据需保留不动。
- **幂等性**：重试时如果 OSS 上传已成功但 DB 更新失败，重新上传 OSS 会覆盖。对策：Consumer 开头检查 status 是否已为 2。

#### 验收标准

- [ ] 生产者发送消息后 ConfirmCallback 正常回调，ack=false 时消息写入 `task_retry_log`
- [ ] 消费者成功处理后调用 `basicAck`，失败后调用 `basicNack` 并触发重试
- [ ] 重试 3 次仍失败后消息进入 DLQ，`TaskCompensationService` 可扫描并重新投递
- [ ] 状态流转被强制执行：`0→1→2` 或 `0→1→DLQ(经补偿)→1→2`，非法跳转打印 WARN 并拒绝
- [ ] `./gradlew bootRun` 正常启动，`POST /api/file/upload` + `GET /api/file/list` 正常

---

### Phase 2：大文件上传工程化

**目标**：支持 GB 级文件上传，实现 MD5 秒传、分片上传、断点续传。

**预计工时**：5-7 天

#### 改动清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `controller/FileController.java` | **保留不变** | 原简单上传接口保留给 ≤50MB 快速上传场景 |
| `entity/FileInfo.java` | **扩展** | 新增 `fileMd5`（VARCHAR(32)）、`totalChunks`（INT）、`chunkSize`（BIGINT）、`ossUploadId`（VARCHAR(128)）字段 |
| `entity/FileDocument.java` | **扩展** | 新增 `fileMd5` 字段同步到 ES，便于去重查询 |
| `service/OssService.java` | **扩展** | 新增 `initMultipartUpload()`、`uploadPart()`、`completeMultipartUpload()`、`abortMultipartUpload()` 四个方法，复用同一个 `OSS` 客户端实例（改为 `@PostConstruct` 初始化） |

#### 新增文件

| 文件 | 包路径 | 说明 |
|------|--------|------|
| `controller/ChunkUploadController.java` | `controller` | 分片上传专用控制器，4 个端点（见下方接口表） |
| `dto/ChunkInitRequest.java` | `dto` | 分片初始化请求：`{fileName, fileSize, fileMd5, totalChunks, chunkSize}` |
| `dto/ChunkInitResponse.java` | `dto` | 分片初始化响应：`{fileId, uploadId, uploadedChunks[]}`（已传分片索引列表用于断点续传） |
| `dto/ChunkMergeRequest.java` | `dto` | 合并请求：`{fileId}` |
| `dto/Md5CheckRequest.java` | `dto` | MD5 校验请求：`{fileMd5}` |
| `dto/Md5CheckResponse.java` | `dto` | MD5 校验响应：`{exists, fileId, ossUrl}`（秒传时直接返回） |
| `entity/FileChunk.java` | `entity` | 分片记录实体（fileId, fileMd5, chunkIndex, ossEtag, status） |
| `mapper/FileChunkMapper.java` | `mapper` | 分片记录 Mapper（insert, findByMd5, findByFileId, updateEtag） |
| `service/ChunkUploadService.java` | `service` | 分片核心逻辑：md5Check → init → upload → merge，封装 OSS multipart API |
| `utils/Md5Util.java` | `utils` | **流式** MD5 计算工具（`MessageDigest` + `FileInputStream` 分批读取，支持大文件，不占内存） |

#### 新增表

```sql
-- 分片上传记录表
CREATE TABLE file_chunk (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    file_id         BIGINT       NOT NULL,
    file_md5        VARCHAR(32)  NOT NULL,
    chunk_index     INT          NOT NULL,
    chunk_size      BIGINT,
    oss_upload_id   VARCHAR(128),
    oss_part_number INT,
    oss_etag        VARCHAR(64),
    status          TINYINT      DEFAULT 0,  -- 0=上传中, 1=已完成
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_file_md5 (file_md5),
    INDEX idx_file_id (file_id)
);
```

`file_info` 表新增字段（ALTER TABLE）：

```sql
ALTER TABLE file_info ADD COLUMN file_md5     VARCHAR(32)   AFTER file_size;
ALTER TABLE file_info ADD COLUMN total_chunks INT DEFAULT 1 AFTER file_md5;
ALTER TABLE file_info ADD COLUMN chunk_size   BIGINT         AFTER total_chunks;
ALTER TABLE file_info ADD COLUMN oss_upload_id VARCHAR(128)  AFTER chunk_size;
```

#### 新增接口

| 接口 | 方法 | 请求体 / 参数 | 响应 | 说明 |
|------|------|---------------|------|------|
| `/api/file/chunk/check` | POST | `{fileMd5}` | `{exists:bool, fileId, ossUrl, uploadedChunks:[]}` | **MD5 秒传校验**：exists=true 直接秒传返回已有文件；exists=false 返回已传分片索引列表（断点续传） |
| `/api/file/chunk/init` | POST | `{fileName, fileSize, fileMd5, totalChunks, chunkSize}` | `{fileId, uploadId}` | **初始化分片上传**：创建 FileInfo + 在 OSS 创建 multipart upload |
| `/api/file/chunk/upload` | POST | form-data: `chunk`(file) + `fileId` + `chunkIndex` | `{chunkIndex, etag}` | **上传单个分片**：通过 OSS uploadPart 上传 |
| `/api/file/chunk/merge` | POST | `{fileId}` | `{fileId, ossUrl}` | **合并分片**：OSS completeMultipartUpload → 发 MQ 消息 → 进入 Phase 1 异步处理链路 |

#### 涉及配置变更

`application.yml` 调整：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 200MB      # 单分片最大 200MB（从 50MB 上调）
      max-request-size: 200MB

file:
  chunk-size: 5242880            # 分片大小默认 5MB（客户端可覆盖）
  upload-dir: ${FILE_UPLOAD_DIR:uploads/chunks}
```

#### 风险点

- **OSS 分片合并失败**：`completeMultipartUpload` 可能因 etag 顺序错误失败，需校验 partNumber 顺序。对策：`ChunkUploadService.merge()` 中按 chunkIndex 排序 etag 数组后提交。
- **孤儿分片**：OSS 上已初始化但未完成/取消的 multipart upload 会产生存储费用。对策：`@Scheduled` 定时扫描 `file_chunk` 表中超过 24h 未完成的记录，调用 `abortMultipartUpload()`。
- **MD5 碰撞**：理论上不同文件 MD5 相同概率极低，但需考虑业务场景。对策：秒传时不仅校验 MD5，还需校验 `fileSize` 一致。
- **前端改造依赖**：本阶段只做后端。前端暂用单文件上传兼容，分片接口准备好后可独立验证（curl/Postman）。

#### 验收标准

- [ ] `POST /chunk/check` 查询不存在的 MD5 返回 `exists=false, uploadedChunks=[]`
- [ ] 上传 3 个分片后再 `/chunk/check`，返回 `uploadedChunks=[0,1,2]`
- [ ] 模拟中断后 `/chunk/check`，仅返回已成功的分片索引
- [ ] 补传缺失分片后调 `/chunk/merge`，合并成功，OSS 可见完整文件，异步处理链路正常
- [ ] MD5 相同的文件再次调 `/chunk/check` 返回 `exists=true`（秒传）
- [ ] 500MB 文件上传全流程不 OOM（IDE 中观察堆内存）
- [ ] 原有 `POST /api/file/upload` 仍正常工作

---

### Phase 3：搜索数据一致性 — Canal 同步

**目标**：引入 Canal 监听 MySQL binlog，实现 MySQL → ES 的最终一致性同步，消除手动双写。

**预计工时**：4-6 天

#### 改动清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `service/FileUploadConsumer.java` | **简化** | `handleFileUpload()` 中删除 `elasticsearchClient.index()` 调用（约 7 行），ES 同步完全交给 Canal |
| `controller/SearchController.java` | **重构** | 将 ES 查询逻辑提取到新 service，本 Controller 只做参数接收和结果包装 |

#### 新增文件

| 文件 | 包路径 | 说明 |
|------|--------|------|
| `config/CanalConfig.java` | `config` | Canal 连接配置（host, port, destination, batchSize），`@ConfigurationProperties(prefix = "canal")` |
| `canal/CanalClient.java` | `canal` | Canal 客户端生命周期管理：`@PostConstruct` 启动连接 → `CanalConnector` 订阅 binlog → 分发事件到 EventHandler → `@PreDestroy` 断开；内置断线重连（指数退避） |
| `canal/FileInfoEventHandler.java` | `canal` | `file_info` 表变更事件处理器：INSERT→ES index, UPDATE→ES update, DELETE→ES delete；记录 `es_sync_log` |
| `canal/EsSyncService.java` | `canal` | ES 索引 CRUD 的封装（对接 `FileInfoEventHandler`），处理异常重试 |
| `canal/EsReconciliationService.java` | `canal` | `@Scheduled` 定时对账（默认每天凌晨 3 点）：分页扫 MySQL `file_info` → 逐条查 ES → 比较 revision → 发现差异写入补偿队列 |
| `service/EsSearchService.java` | `service` | 从 `SearchController` 中抽离出的搜索逻辑：`searchFiles(keyword, page, size)` + `buildQuery()` + `enrichFromDb()` |

#### 新增表

```sql
-- ES 同步日志（对账用）
CREATE TABLE es_sync_log (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    file_id      BIGINT       NOT NULL,
    sync_type    VARCHAR(20)  NOT NULL,  -- INSERT / UPDATE / DELETE
    sync_status  VARCHAR(20)  DEFAULT 'PENDING',  -- PENDING / SUCCESS / FAILED
    error_msg    TEXT,
    retry_count  INT          DEFAULT 0,
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_file_id (file_id),
    INDEX idx_status (sync_status, create_time)
);
```

#### 新增接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/admin/es/reindex` | POST | 手动触发全量重建 ES 索引（扫全表 → 批量 index） |
| `/api/admin/es/reconcile` | POST | 手动触发 MySQL ↔ ES 对账一次 |

#### 涉及配置变更

`application.yml` 新增：

```yaml
canal:
  host: ${CANAL_HOST:localhost}
  port: ${CANAL_PORT:11111}
  destination: ${CANAL_DESTINATION:example}
  batch-size: 1000
  retry:
    max-attempts: 5
    initial-interval: 5000
```

#### 基础设施准备

需要额外部署 Canal Server（Docker 方式）：

```bash
docker run -d --name canal-server \
  -e canal.instance.master.address=host.docker.internal:3306 \
  -e canal.instance.dbUsername=canal \
  -e canal.instance.dbPassword=canal \
  -e canal.instance.connectionCharset=UTF-8 \
  -e canal.instance.filter.regex=file_processor\\.file_info \
  -p 11111:11111 \
  canal/canal-server:latest
```

MySQL 需要提前创建 Canal 专用账号并授权：

```sql
CREATE USER 'canal'@'%' IDENTIFIED BY 'canal';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
```

> **注意**：Canal 要求 MySQL `binlog_format=ROW`（MyQL 8.0 默认值，但需确认）。

#### 风险点

- **Canal 连接断开**：网络抖动或 MySQL 重启导致 Canal 连接中断。对策：`CanalClient` 实现指数退避重连，并从上次记录的 binlog 位点继续。
- **ES 写入失败**：ES 集群不可用时 Canal 的 INSERT 事件无法写入。对策：写入失败时记录 `es_sync_log` 并设置 `sync_status=FAILED`，对账任务会补偿。
- **binlog 权限**：部分云数据库不允许开启 REPLICATION 权限，需提前确认。如果无法用 Canal，备选方案是纯定时对账（扫全表 diff），虽不如 binlog 实时但因业务量小也够用。
- **历史数据**：Canal 只处理启动后的增量变更，历史存量数据需通过 `/api/admin/es/reindex` 全量同步。

#### 验收标准

- [ ] MySQL `file_info` 表 INSERT 后 3 秒内 ES 中可查到该文档
- [ ] MySQL `file_info` 表 UPDATE（如 status 变更）后 ES 文档同步更新
- [ ] MySQL `file_info` 表 DELETE 后 ES 文档被删除
- [ ] 手动关闭 Canal 后，`CanalClient` 30 秒内自动重连并补齐遗漏的 binlog
- [ ] `POST /api/admin/es/reconcile` 返回差异列表；`POST /api/admin/es/reindex` 全量重建成功
- [ ] 原有 `GET /api/search?keyword=xxx` 搜索结果正常

---

### Phase 4：JVM 优化与压测

**目标**：大文件流式解析不 OOM，产出可展示的性能测试数据。

**预计工时**：3-4 天

#### 改动清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `service/FileContentExtractor.java` | **重构** | PDF 提取从 `Loader.loadPDF(File)` 改为 `Loader.loadPDF(inputStream)` 流式；增加 `extractContentStreaming(InputStream, String fileType, Consumer<String> chunkHandler)` 方法，逐块读取不堆积内存 |
| `service/FileUploadConsumer.java` | **调整** | 调用 `extractContentStreaming`，边提取边写入 DB 的 content 字段（追加模式） |
| `service/OssService.java` | **优化** | `OSS` 客户端改为 `@PostConstruct` 初始化单例，避免每次上传创建/销毁连接 |

#### 新增文件

| 文件 | 包路径 | 说明 |
|------|--------|------|
| `utils/StreamingContentExtractor.java` | `utils` | 流式提取抽象层：`extract(InputStream, FileType, int bufferSize, BiConsumer<Integer, String>)`，每种文件类型返回一个迭代器式的提取器 |
| `config/MonitorConfig.java` | `config` | Micrometer 指标暴露（`MeterRegistry` Bean）；自定义指标：`file.upload.count`, `file.extract.duration`, `file.chunk.upload.count` |
| `jmeter/file-upload-test.jmx` | `jmeter/` | JMeter 压测计划：10 并发 × 循环 20 次上传 10MB PDF → 监控 TPS/RT/错误率 |
| `jmeter/search-test.jmx` | `jmeter/` | JMeter 压测计划：50 并发关键字搜索 → 监控 RT 分位数 |
| `jmeter/chunk-upload-test.jmx` | `jmeter/` | JMeter 压测计划：5 并发 × 200MB 分片上传 → 监控吞吐量 |
| `docs/PERFORMANCE.md` | `docs/` | **压测报告**（模板包含：环境配置、JMeter 截图、TPS 曲线、RT 分位数表、JVM 堆内存曲线、GC 日志分析、结论与优化建议） |

#### 涉及配置变更

`application.yml` 新增：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics, info
  metrics:
    export:
      simple:
        enabled: true

file:
  stream-buffer-size: 8192    # 流式读取缓冲区大小（字节）
```

JVM 启动参数（写入 README 和 `docs/PERFORMANCE.md`，不硬编码在 application.yml 中）：

```
-Xms512m -Xmx1024m
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-Xlog:gc*:file=logs/gc.log:time,level,tags
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=logs/
```

#### 新增外部依赖

`build.gradle` 新增：

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'  // 可选，对接 Prometheus
```

#### 风险点

- **PDFBox 流式限制**：加密 PDF 或损坏 PDF 流式读取可能抛异常，需要降级为全量加载或跳过。对策：`StreamingContentExtractor` 中 catch `CryptographyException` 特殊处理。
- **压测环境隔离**：压测会写入大量数据到 MySQL/ES。对策：使用独立的 `file_processor_test` 库或压测前备份数据。
- **GC 日志路径**：Windows 下 `logs/` 需确保目录存在或有写权限。

#### 验收标准

- [ ] 200MB PDF 文件流式解析，堆内存峰值 ≤ 256MB（VisualVM 或 JConsole 截图）
- [ ] 200MB Word 文件流式解析，堆内存峰值 ≤ 256MB
- [ ] GC 日志显示无 Full GC，YGCT 占比 > 95%
- [ ] JMeter 压测报告产出：上传 TPS ≥ 50（小文件），搜索 RT P99 ≤ 200ms
- [ ] `/actuator/health` 返回 UP
- [ ] 10 个 50MB 文件并发上传不 OOM

---

### Phase 5：AI / RAG 扩展

**目标**：文档上传后自动文本切片，支持基于文档内容的问答（为后续接入 LLM + 向量库预留接口）。

**预计工时**：5-8 天

#### 改动清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `service/FileUploadConsumer.java` | **扩展** | 在 "提取文本内容" 之后、"更新 DB" 之前插入文本切片步骤 |
| `entity/FileInfo.java` | 不改 | — |

#### 新增文件

| 文件 | 包路径 | 说明 |
|------|--------|------|
| `service/TextChunkService.java` | `service` | 文本切片服务：支持 3 种切分策略 — `PARAGRAPH`（按段落）、`SENTENCE`（按句子）、`FIXED`（按固定字符数 + overlap）；返回 `List<TextChunk>` |
| `entity/TextChunk.java` | `entity` | 文本块实体（id, fileId, chunkIndex, chunkContent, chunkSize, chunkHash） |
| `mapper/TextChunkMapper.java` | `mapper` | 文本块 Mapper（batchInsert, findByFileId, deleteByFileId） |
| `controller/QaController.java` | `controller` | QA 问答控制器（2 个端点，见下方接口表） |
| `dto/QaRequest.java` | `dto` | 问答请求：`{question, topK=5}` |
| `dto/QaResponse.java` | `dto` | 问答响应：`{answer, sources: [{fileId, fileName, chunkContent, score}]}` |
| `service/QaService.java` | `service` | 问答核心逻辑：**Phase 5 先做关键词匹配版**。将 question 分词后对 `text_chunk` 表做全文匹配（MySQL `LIKE` 或 ES `match`），Top-K 排序返回。`QaService` 设计为接口+实现模式（`QaService` 接口 + `KeywordQaServiceImpl`），后续接入向量检索时只要新增 `VectorQaServiceImpl` 实现即可。 |
| `service/SseEmitterService.java` | `service` | SSE 流式响应封装：`SseEmitter` 的创建、发送、完成、异常处理、超时管理 |
| `config/SseConfig.java` | `config` | SSE 超时配置（默认 120s） |

#### 新增表

```sql
-- 文本块表
CREATE TABLE text_chunk (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    file_id        BIGINT       NOT NULL,
    chunk_index    INT          NOT NULL,
    chunk_content  TEXT         NOT NULL,
    chunk_size     INT          DEFAULT 0,
    chunk_hash     VARCHAR(64),                         -- SHA-256，用于去重
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_file_id (file_id),
    INDEX idx_chunk_index (file_id, chunk_index),
    FULLTEXT INDEX ft_chunk_content (chunk_content)     -- MySQL 全文索引用于关键词匹配
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 新增接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `POST /api/qa/ask` | POST | `{question, topK}` → 返回 `{answer, sources[]}`，基于 MySQL FULLTEXT 或 ES 关键词匹配 |
| `GET /api/qa/ask/stream` | GET | `?question=xxx&topK=5` → SSE 流式响应（Phase 5 模拟流式输出匹配到的 chunks，后续接入 LLM 时替换为真实 token 流） |
| `GET /api/qa/chunks/{fileId}` | GET | 查看某文件的文本切片列表（分页） |

#### 涉及配置变更

`application.yml` 新增：

```yaml
text-chunk:
  chunk-size: 512              # 每块最大字符数
  chunk-overlap: 64            # 块间重叠字符数
  split-strategy: sentence     # 切分策略：sentence / paragraph / fixed

# 后续接入 LLM 时的预留配置（Phase 5 暂不启用）
# llm:
#   provider: openai
#   api-key: ${LLM_API_KEY:}
#   base-url: ${LLM_BASE_URL:https://api.openai.com/v1}
#   model: gpt-4o-mini
#   max-tokens: 2048
#   temperature: 0.7
```

#### 风险点

- **中文分词**：MySQL FULLTEXT 对中文支持有限（需 ngram parser）。对策：建表时指定 `WITH PARSER ngram`，或直接使用 ES 做关键词匹配（利用 `standard` 分词器）。
- **切片策略选型**：段落切分对 Markdown/Word 友好但对 PDF 可能不准（PDF 段落边界模糊）。对策：`split-strategy: sentence` 作为默认策略最通用。
- **SSE 连接管理**：大量 SSE 长连接可能占满 Tomcat 线程池。对策：`SseConfig` 中合理设置超时，必要时切换为 WebFlux 或使用虚拟线程（Java 21+）。
- **接口设计前瞻性**：`QaService` 设计为接口后，实现从关键词匹配切换到向量检索时，Controller 无需任何改动。

#### 验收标准

- [ ] 上传 10KB TXT 文件后，`text_chunk` 表自动生成 ≥1 条切片记录
- [ ] 上传 100 页 PDF 后，切片数 ≥50（按 sentence 策略）
- [ ] `POST /api/qa/ask` 输入问题能返回相关文件片段（sources 不为空）
- [ ] `GET /api/qa/ask/stream` 通过 `curl -N` 可观察到 SSE 事件流
- [ ] `GET /api/qa/chunks/{fileId}` 返回该文件所有切片，支持分页
- [ ] 切换 `text-chunk.split-strategy` 配置后，新上传文件的切片策略生效

---

### Phase 6：GitHub 展示与文档工程

**目标**：打造专业级开源项目展示面，对面试官友好、对社区友好。

**预计工时**：3-4 天

#### 改动清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `README.md` | **重写** | 按第五章推荐结构重写（中英双语或中文 + 英文摘要），约为原 README 3-5 倍篇幅 |
| `.gitignore` | **扩展** | 新增排除规则：`logs/`, `*.log`, `uploads/`（保留目录不保留文件）, `.env` |

#### 新增文件

| 文件 | 说明 |
|------|------|
| `README_EN.md` | 英文版 README（精简版，面向国际社区） |
| `docs/ARCHITECTURE.md` | 架构设计文档：数据流图 + 组件交互说明 + 技术选型理由 |
| `docs/API.md` | 全部 API 接口文档（Markdown 表格 + curl 示例 + 响应示例） |
| `docs/ROADMAP.md` | 项目路线图：已完成 ✅ / 进行中 🚧 / 计划中 📋 三栏 |
| `docs/CHANGELOG.md` | 版本变更日志（从 v0.2.0 开始记录，v0.1.0 标记为初始版） |
| `docs/PERFORMANCE.md` | 性能测试报告（Phase 4 产出的数据，含截图和方法论） |
| `docs/assets/architecture.png` | 架构图（推荐 draw.io 绘制，导出 png，源码 .drawio 也入库） |
| `docs/assets/dataflow.png` | 数据流图（Chunk 上传流 + 异步处理流 + 搜索流 + QA 流） |
| `docs/assets/screenshots/homepage.png` | 首页截图 |
| `docs/assets/screenshots/upload.png` | 上传功能截图 |
| `docs/assets/screenshots/search.png` | 搜索功能截图 |
| `docs/assets/screenshots/qa.png` | QA 问答截图（Phase 5 后） |
| `docs/assets/screenshots/performance.png` | 压测报告中的关键图表 |
| `.github/workflows/ci.yml` | GitHub Actions CI：`checkout → setup-java 17 → setup-gradle → ./gradlew build` |
| `.env.example` | 环境变量模板（空值占位，不包含真实密钥） |

#### 验收标准

- [ ] 新访客看 README 的 **前 30 秒** 能看懂：这项目做什么 + 架构长什么样 + 怎么跑起来
- [ ] README 架构图清晰展示：用户→Spring Boot→MySQL/ES/RabbitMQ/OSS + Canal + AI 模块
- [ ] `docs/API.md` 涵盖所有接口，每条有 curl 示例和响应 JSON
- [ ] CI Badge 显示绿色 `build passing`
- [ ] ROADMAP 中 Phase 1-6 均有标记（已完成/进行中/计划中）
- [ ] 项目截图尺寸统一（推荐 1200×700），WebP 格式

---

## 四、Git 分支规划与提交粒度

### 4.1 分支策略

```
main                              ← 始终可运行，只接受 PR 合并
  ├── feature/phase1-reliable-mq       ← Phase 1
  ├── feature/phase2-chunk-upload      ← Phase 2
  ├── feature/phase3-canal-sync        ← Phase 3
  ├── feature/phase4-jvm-optimize      ← Phase 4
  ├── feature/phase5-ai-rag            ← Phase 5
  └── feature/phase6-docs-showcase     ← Phase 6
```

每个 feature 分支从 `main` 的当前 HEAD 切出，完成后合并回 `main`，然后删除 feature 分支。不设 `develop` 分支（单人项目维护多分支成本高于收益）。

### 4.2 推荐提交粒度（以 Phase 2 为例）

```
feature/phase2-chunk-upload:
  #1  feat: add Md5Util with streaming MD5 calculation
      └── utils/Md5Util.java

  #2  feat: add chunk-related DTOs (ChunkInit, ChunkMerge, Md5Check)
      └── dto/ChunkInitRequest.java, ChunkInitResponse.java,
          dto/ChunkMergeRequest.java, dto/Md5CheckRequest.java,
          dto/Md5CheckResponse.java

  #3  feat: add FileChunk entity and FileChunkMapper
      └── entity/FileChunk.java, mapper/FileChunkMapper.java

  #4  feat: extend FileInfo with MD5/chunk/ossUploadId fields
      └── entity/FileInfo.java

  #5  feat: extend OssService with multipart upload API
      └── service/OssService.java (新增 4 个方法)

  #6  feat: add ChunkUploadService core logic
      └── service/ChunkUploadService.java

  #7  feat: add ChunkUploadController with /api/file/chunk/* endpoints
      └── controller/ChunkUploadController.java

  #8  test: add chunk upload integration tests
      └── src/test/.../ChunkUploadServiceTest.java, ChunkUploadControllerTest.java

  #9  chore: update application.yml with chunk upload config
      └── application.yml (chunk-size, upload-dir 调整)

  #10 docs: update README with chunk upload API docs
      └── README.md
```

### 4.3 Commit Message 规范

采用 Conventional Commits：

```
<type>: <简短描述>

type 可选值：
  feat      — 新功能
  fix       — Bug 修复
  refactor  — 重构（不改变对外行为）
  test      — 测试相关
  docs      — 文档
  chore     — 构建/配置/依赖变更
  perf      — 性能优化
```

示例：
- `feat: add MD5-based instant upload check endpoint`
- `fix: resolve OSS client connection leak in multipart upload`
- `docs: add architecture diagram to README`
- `perf: switch PDF extraction to streaming mode to reduce heap usage`

---

## 五、README 展示结构建议

### 5.1 推荐结构（从上到下）

```markdown
# Smart File Processor — 企业级智能文档处理与知识库问答平台

<!-- Badge 行：Java 17 | Spring Boot 3.2 | License MIT | Build Passing | Stars -->

## 📖 目录                                    ← 锚点导航

## 1. 项目简介                                ← 2-3 句话说清定位
   （支持什么文件格式、解决什么痛点、技术路线）

## 2. 技术亮点                                ← 6 个卡片式展示
   🚀 大文件分片上传 | 📨 可靠异步任务 | 🔍 全文检索
   📊 数据一致性 | 🧠 AI 文档问答 | ⚡ 流式处理

## 3. 架构图                                  ← 核心！放在前 1/3 处
   ![architecture](docs/assets/architecture.png)
   （图下方用 3-4 句话说明关键数据流）

## 4. 快速开始                                ← Docker Compose 拉齐
   ```bash
   git clone ... && cd file-processor
   docker-compose up -d   # MySQL + RabbitMQ + ES + Canal
   ./gradlew bootRun
   ```

## 5. 功能模块                                ← 每个模块一节
   ### 5.1 大文件上传（MD5 秒传 / 分片 / 断点续传）
   ### 5.2 异步任务引擎（RabbitMQ + DLQ + 状态机）
   ### 5.3 全文检索（ES + Canal 数据一致性）
   ### 5.4 AI 文档问答（文本切片 + RAG + SSE 流式）

## 6. API 速查表                              ← 一屏看完所有接口
   | 接口 | 方法 | 说明 |
   （详情指向 docs/API.md）

## 7. 技术栈                                  ← 后端 / 中间件 / 前端 / 监控
## 8. 项目结构                                ← 目录树 + 每个包一句话说明
## 9. 性能测试                                ← TPS/RT 截图 + 结论
## 10. 项目截图                               ← 4 张关键截图
## 11. Roadmap                                ← ✅ 已完成 / 🚧 进行中 / 📋 计划中
## 12. 技术博客                               ← 掘金/博客园/个人博客链接
## 13. 关于作者                               ← 简介 + GitHub + 联系方式
## 14. License                                ← MIT
```

### 5.2 关键原则

- **架构图放在 README 前 1/3**：面试官 5 秒内就能看到技术全貌。
- **API 速查表一屏看完**：不要列所有字段，接口路径 + 一句话说明即可，详情指向 `docs/API.md`。
- **性能测试要有真实数字**：TPS 曲线截图 + P99 分位数表 + 结论（"在 4C8G 服务器上，10 并发上传 10MB PDF，TPS=XX，P99=XXms"）— "压测通过"没有说服力。
- **项目截图统一风格**：1200×700 尺寸，优先 WebP 格式，带浏览器地址栏。
- **Roadmap 诚实标注**：已完成标 ✅，进行中标 🚧，计划中标 📋。不要全都写"已完成"。
- **技术博客链接**：如果有写相关博客，放链接到掘金/博客园/个人博客，展示技术写作能力。

---

## 六、总结

### 6.1 各阶段依赖关系

```
Phase 1 (可靠消息) ──────┐
                         ├──→ Phase 3 (Canal 一致性) ──→ Phase 5 (AI/RAG)
Phase 2 (大文件上传) ────┘             │
                         └──→ Phase 4 (JVM 优化)

Phase 6 (GitHub 展示) ← 贯穿全流程，每个 Phase 完成后增量更新文档
```

- **Phase 1 和 Phase 2 无依赖关系**，可以并行推进。
- **Phase 3 依赖 Phase 1**：可靠消息链路上的 Consumer 已稳定，才能放心地把 ES 同步权交给 Canal。
- **Phase 4 可与 Phase 3 并行**：一个改解析层，一个改同步层，互不冲突。
- **Phase 5 建议在 Phase 1+2+3 之后**：有可靠的处理链路 + 大文件能力 + ES 数据一致性，RAG 才有稳定基础。
- **Phase 6 每个 Phase 结束时增量更新**（README, CHANGELOG, ROADMAP, 截图），不单独占用一个完整周期。

### 6.2 总工作量估算

| Phase | 内容 | 预计工时 |
|-------|------|----------|
| Phase 1 | 可靠消息与任务状态机 | 3-5 天 |
| Phase 2 | 大文件上传工程化 | 5-7 天 |
| Phase 3 | 搜索数据一致性 (Canal) | 4-6 天 |
| Phase 4 | JVM 优化与压测 | 3-4 天 |
| Phase 5 | AI / RAG 扩展 | 5-8 天 |
| Phase 6 | GitHub 展示与文档 | 3-4 天 |
| **合计** | | **23-34 天** |

### 6.3 最核心的改造成果

完成全部 6 个 Phase 后，项目的简历展示价值将从"一个能跑的文件上传 Demo"升级为：

> **"独立设计并实现的企业级智能文档处理平台，涵盖大文件分片上传、RabbitMQ 可靠消息、Canal 数据一致性同步、Elasticsearch 全文检索、JVM 流式处理优化、AI 文档问答等后端核心技术栈，附完整的架构文档和压测报告。"**

---

> **文档结束** · 下一步：从 Phase 1 开始实现，或根据优先级调整 Phase 顺序。
