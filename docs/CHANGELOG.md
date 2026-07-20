# 变更日志

> Smart File Processor · CHANGELOG
> 按时间倒序，记录每个 Phase 的关键变化。

---

## Phase 3 · 代码质量加固（2026-07-15）

### 闭环 1：全局异常处理 + 消除重复代码
- **新增** `exception/GlobalExceptionHandler.java` — @RestControllerAdvice 统一拦截 Controller 层异常
  - MultipartException / MaxUploadSizeExceededException → 文件上传失败
  - MethodArgumentNotValidException → 参数校验失败（预留 @Valid 扩展）
  - IOException → 文件操作失败
  - AmqpException → 消息队列异常
  - IllegalArgumentException → 参数错误
  - Exception → 兜底
- **新增** `utils/FileTypeUtil.java` — 统一文件类型判断工具类
  - null 安全、无扩展名回退
  - 合并 3 处原有 getFileType 逻辑（FileController / FileUploadConsumer / ChunkUploadService）
- **修改** `controller/FileController.java` — 移除 upload() 中的 try-catch，异常上抛给全局处理器；改用 FileTypeUtil
- **修改** `service/FileUploadConsumer.java` — 改用 FileTypeUtil（异常处理逻辑不变）
- **修改** `service/ChunkUploadService.java` — 改用 FileTypeUtil

### 闭环 2：OSS 客户端单例 + 文件列表分页
- **重写** `service/OssService.java` — @PostConstruct 初始化 OSS 客户端、@PreDestroy 销毁，全生命周期复用
- **新增** `dto/PageResult.java` — 通用分页响应 DTO（list/total/page/size/totalPages）
- **修改** `mapper/FileInfoMapper.java` — 新增 findPage(offset, size) + count()
- **修改** `service/FileInfoService.java` — 新增 getPage(page, size)
- **修改** `controller/FileController.java` — list() 支持 page/size 参数，无参数时返回全量数组（向后兼容）

### 闭环 3：Docker Compose + .env.example
- **新增** `docker-compose.yml` — MySQL 8.0 + RabbitMQ 3.13-management + Elasticsearch 8.12（单节点）
  - 全部含 healthcheck、volume 持久化、自定义网络
- **新增** `.env.example` — 13 个环境变量模板
- **修改** `README.md` — 快速启动章节改用 `docker compose up -d`

### 闭环 4：测试补齐
- **新增** `controller/FileControllerTest.java` — 8 个测试
  - upload 正常/异常、list 无参/分页/边界、getById 存在/不存在
  - MockMvcBuilders.standaloneSetup 模式，不加载 Spring 上下文
- **新增** `service/ChunkUploadServiceTest.java` — 15 个测试
  - md5Check 命中/未命中/参数错误、initChunkUpload 新建/复用/参数错误
  - uploadChunk 正常/幂等/参数错误、mergeChunks 分片不足/状态错误/文件不存在/参数错误
  - 纯 Mockito + @TempDir 处理文件 I/O
- **测试总数**：23（从 1 个 contextLoads 冒烟测试增长）

### 闭环 5：文档同步
- **重写** `README.md` — 项目定位调整为企业级文件处理与检索系统、RabbitMQ 可靠消息 Mermaid 图、分页 API、测试跑分 Badge、Backlog 明确标记
- **重写** `docs/roadmap.md` — Phase 1/2/3 标记已完成、Phase 4→Canal、Phase 5→JVM、RAG/AI→Backlog
- **重写** `docs/architecture.md` — v2.0 版，补充分片上传链路、RabbitMQ 可靠链路详细图、双状态机、全局异常处理、文件结构树
- **重写** `docs/interview-notes.md` — 修正 ACK 描述、新增 MD5 秒传/分片上传/全局异常/测试/分页等多种后端面试话题
- **新增** `docs/CHANGELOG.md` — 本文件

---

## Phase 2 · 大文件上传工程化（2026-07-15）

### 闭环 2：MD5 秒传
- **新增** `dto/Md5CheckRequest.java` — fileMd5 + fileSize + originalFilename
- **新增** `dto/Md5CheckResponse.java` — instantUpload + fileId + fileName + ossUrl + message
- **新增** `service/ChunkUploadService.md5Check()` — 参数校验 → 按 md5+size 查询 → hit/miss 响应

### 闭环 3：分片上传
- **新增** `dto/ChunkInitRequest.java` / `ChunkInitResponse.java`
- **新增** `dto/ChunkUploadResponse.java`
- **新增** `entity/FileChunk.java` — 9 字段（id/fileId/fileMd5/chunkIndex/chunkSize/chunkPath/uploadStatus/createdAt/updatedAt）
- **新增** `entity/FileChunkStatus.java` — UPLOADED / FAILED 枚举
- **新增** `mapper/FileChunkMapper.java` — insertOrUpdate (ON DUPLICATE KEY UPDATE) / findByFileMd5 / countUploadedChunks
- **新增** `controller/ChunkUploadController.java` — POST /check /init /upload /merge
- **新增** `service/ChunkUploadService.initChunkUpload()` / `uploadChunk()`（流式写入 .part、8KB 缓冲区）

### 闭环 4：分片合并
- **新增** `dto/ChunkMergeRequest.java` / `ChunkMergeResponse.java`
- **新增** `service/ChunkUploadService.mergeChunks()` — 状态校验 → 分片完整性检查 → 流式合并 → MessageDigest MD5 校验 → 事务更新 → 发 MQ → 清理 .part
- **新增** 双状态枚举：`enums/UploadStatusEnum.java`（INIT/UPLOADING/READY_TO_MERGE/MERGING/UPLOADED/MERGE_FAILED）
- **新增** `enums/ParseStatusEnum.java`（NOT_PARSED/WAITING_PARSE/PARSING/PARSE_SUCCESS/PARSE_FAILED）
- **修改** `entity/FileInfo.java` — 新增 8 字段（fileMd5/storagePath/uploadStatus/parseStatus/totalChunks/uploadedChunks/isChunked/mergeTime）
- **修改** `mapper/FileInfoMapper.java` — 新增 findByMd5AndSizeAndStatus/findByMd5AnyStatus/updateChunkProgress/updateParseStatus/updateMergeInfo
- **修改** `service/FileInfoService.java` — 新增 saveChunkedFileInfo/updateChunkProgress/completeMerge 等

### 新增文档
- `docs/upload-design.md` — 状态设计、4 闭环拆分、完整数据流
- `docs/upload-test-guide.md` — curl 测试场景
- `docs/sql/init-file_info-v2.sql` / `init-file_chunk.sql` / `alter-file_info-v2.sql`

---

## Phase 1 · RabbitMQ 可靠性增强（2026-07-14 ~ 2026-07-15）

### 闭环 2：MANUAL ACK + Spring Retry
- **修改** `application.yml` — acknowledge-mode: manual, prefetch: 1, default-requeue-rejected: false, retry: 3 次 2s→4s
- **重写** `service/FileUploadConsumer.java` — 3 路径异常处理：成功 basicAck / FileNotFoundException basicNack(requeue=false) / 其他异常 throw → Spring Retry

### 闭环 3：DLX/DLQ
- **修改** `config/RabbitMqConfig.java` — 声明 file.upload.dlx.exchange + file.upload.dlq + DLX 绑定
- **新增** `service/DeadLetterConsumer.java` — @RabbitListener 监听 DLQ，提取 x-death header，记录 DB PARSE_FAILED

### 闭环 4：Producer Confirm
- **修改** `config/RabbitMqConfig.java` — publisher-confirm-type: correlated, mandatory: true, RabbitTemplate 配置 ConfirmCallback + ReturnsCallback
- **修改** `service/FileUploadProducer.java` — convertAndSend 携带 CorrelationData（file-upload:{fileId}:{8位uuid}）+ deliveryMode=PERSISTENT
- **修改** `application.yml` — publisher-confirm-type, publisher-returns

### 新增文档
- `docs/rabbitmq-reliability-review.md` — 732 行工程复盘，覆盖 3 层保障、7 种故障分支、x-death header 文档

---

## Phase 0 · 基础平台（2026-07-10 ~ 2026-07-14）

### 初始功能
- Spring Boot 3.2 + Gradle 8.5 项目骨架
- `controller/FileController.java` — POST /api/file/upload, GET /api/file/list, GET /api/file/{id}
- `controller/SearchController.java` — GET /api/search?keyword=xxx
- `mapper/FileInfoMapper.java` — MyBatis 注解式 CRUD
- `service/FileInfoService.java` — 文件信息 CRUD
- `service/OssService.java` — 阿里云 OSS 上传
- `service/FileContentExtractor.java` — PDF/Word/TXT 文本提取
- `service/FileUploadProducer.java` + `FileUploadConsumer.java` — RabbitMQ 基础消息队列
- `entity/FileInfo.java` — 9 字段 v1 模型
- `entity/FileDocument.java` — ES 文档模型
- `dto/Result.java` — 统一响应体
- `dto/FileUploadMessage.java` — MQ 消息体
- `config/OssConfig.java` + `config/RabbitMqConfig.java`
- `src/main/resources/static/index.html` — Vue 3 + Element Plus 前端 SPA
- `application.yml` — 环境变量注入配置

### 初始文档
- `README.md` — 项目介绍、快速启动
- `docs/architecture.md` — 架构说明 v1.0
- `docs/roadmap.md` — 6 Phase 路线图
- `docs/refactor-plan.md` — 完整改造计划
