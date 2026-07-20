# 项目路线图

> Smart File Processor · Roadmap
> 最后更新：2026-07-15

---

## 总览

```
Phase 0         Phase 1           Phase 2           Phase 3           Phase 4           Phase 5           Backlog
[已完成]  ──→  [可靠消息]  ──→  [大文件上传]  ──→  [代码质量加固]  ──→  [数据一致性]  ──→  [内存安全+性能]  ──→  [RAG/AI]
                  ✅                ✅                ✅                ✅                📋                 💡
```

---

## Phase 0 · 基础平台（✅ 已完成）

**时间**：2026-07-10 ~ 2026-07-14

### 已完成功能

- [x] Spring Boot 3.2 + Gradle 8.5 项目骨架
- [x] 文件上传 API（POST `/api/file/upload`）
- [x] 文件列表/详情 API（GET `/api/file/list`, GET `/api/file/{id}`）
- [x] 全文搜索 API（GET `/api/search`）
- [x] MyBatis 注解式持久层（`file_info` 表 CRUD）
- [x] RabbitMQ 消息队列（`file.upload.queue`，生产者 + 消费者）
- [x] 阿里云 OSS 文件上传
- [x] PDF/Word/TXT 文本内容提取
- [x] Elasticsearch 全文索引与搜索
- [x] Vue 3 + Element Plus 前端单页应用

### 未完成项（已由后续 Phase 解决）

- [x] RabbitMQ 可靠性 → **Phase 1 已完成**
- [x] 大文件支持 → **Phase 2 已完成**
- [x] 全局异常处理 → **Phase 3 已完成**
- [x] 单元测试 → **Phase 3 已完成**（起步 23 tests，Phase 4 → 35 tests，Phase 5 → 55 tests）
- [x] 数据一致性保障 → **Phase 4 已完成**（Transactional Outbox Pattern）
- [x] 性能测试报告 → **Phase 5 已完成**（代码 + 模板）

---

## Phase 1 · 可靠消息与任务状态机（✅ 已完成）

**时间**：2026-07-14 ~ 2026-07-15
**目标**：让 RabbitMQ 链路达到生产级可靠性。

### 功能清单

- [x] RabbitMQ 手动 ACK（`acknowledge-mode: manual`）— **闭环 2**
- [x] 死信队列（DLX + DLQ + `DeadLetterConsumer`）— **闭环 3**
- [x] 消费者 Spring Retry（指数退避，max-attempts=3，2s→4s）— **闭环 2**
- [x] 生产者 Confirm 回调（`publisher-confirm-type: correlated`）— **闭环 4**
- [x] ReturnsCallback（`mandatory: true`，暴露路由失败）— **闭环 4**
- [x] 消息持久化（`deliveryMode=PERSISTENT` + durable exchange/queue）

### 相关文档

- 工程复盘见 [rabbitmq-reliability-review.md](./rabbitmq-reliability-review.md)

---

## Phase 2 · 大文件上传工程化（✅ 已完成）

**时间**：2026-07-15
**目标**：支持 GB 级文件上传，MD5 秒传、分片上传、断点续传。

### 功能清单

- [x] MD5 秒传接口（POST `/api/file/chunk/check`）— **闭环 2**
- [x] 分片初始化接口（POST `/api/file/chunk/init`）— **闭环 3**
- [x] 分片上传接口（POST `/api/file/chunk/upload`，流式落盘）— **闭环 3**
- [x] 分片合并接口（POST `/api/file/chunk/merge`，流式合并 + MD5 校验）— **闭环 4**
- [x] `file_chunk` 表 + `FileChunkMapper`（ON DUPLICATE KEY UPDATE 防重）
- [x] `file_info` 表扩展 8 字段（file_md5, storage_path, upload_status, parse_status, total_chunks, uploaded_chunks, is_chunked, merge_time）
- [x] `UploadStatusEnum` + `ParseStatusEnum` 双状态模型
- [x] 合并后自动触发 RabbitMQ 异步解析链路

### 相关文档

- [upload-design.md](./upload-design.md) — 状态设计与闭环拆分
- [upload-test-guide.md](./upload-test-guide.md) — curl 测试指南

---

## Phase 3 · 代码质量加固（✅ 已完成）

**时间**：2026-07-15
**目标**：提升代码质量与工程化水平，补齐测试覆盖，服务 Java 后端求职展示。

### 功能清单

- [x] **闭环 1**：全局异常处理（GlobalExceptionHandler + @RestControllerAdvice）+ FileTypeUtil 去重
- [x] **闭环 2**：OSS 客户端单例复用（@PostConstruct/@PreDestroy）+ 文件列表分页（PageResult）
- [x] **闭环 3**：Docker Compose 一键部署（MySQL + RabbitMQ + ES + healthcheck）+ .env.example
- [x] **闭环 4**：测试补齐（FileControllerTest 8 + ChunkUploadServiceTest 14，Phase 3 起步 23 tests）
- [x] **闭环 5**：文档同步（README / roadmap / architecture / interview-notes / CHANGELOG）

### 对简历的关键提升

| 能力维度 | Phase 3 产出 |
|------|------|
| 代码质量 | 消除 3 处 getFileType 重复，全局异常统一处理 |
| 资源管理 | OSS 客户端单例（@PostConstruct/@PreDestroy 生命周期管理） |
| 接口设计 | 分页 API 向后兼容设计 |
| 工程化 | Docker Compose 一键启动 + .env 配置管理 |
| 测试能力 | JUnit 5 + Mockito + MockMvc standalone 模式 |
| 文档能力 | 架构图、路线图、面试准备、变更日志 |

---

## Phase 4 · 搜索数据一致性（✅ 已完成）

**时间**：2026-07-16
**目标**：基于 Transactional Outbox Pattern 解决 MySQL 与 Elasticsearch 的最终一致性问题。

### 设计决策

放弃 Canal binlog 方案（额外运维成本、Docker 镜像拉取阻塞），改用 Transactional Outbox：
- **为什么不用同步双写**：MySQL 和 ES 无分布式事务支持，双写必然面临部分失败导致的长期不一致
- **为什么不用 Canal**：引入 Canal Server 增加部署和运维复杂度；binlog 解析有格式兼容风险；项目规模不适合引入 CDC 中间件
- **为什么用 Transactional Outbox**：仅依赖 MySQL 本地事务，outbox_event 与业务数据在同一事务中原子写入，异步同步可重试，实现轻量最终一致性

### 功能清单

- [x] `outbox_event` 表（DDL: `init-outbox_event.sql`）
- [x] `OutboxEvent` / `OutboxEventStatus` / `OutboxEventType` 实体与枚举
- [x] `OutboxEventMapper`（幂等插入 ON DUPLICATE KEY UPDATE、原子抢占 claim、状态更新）
- [x] `OutboxEventService`（事件创建、claim、markSuccess、markFailed、指数退避）
- [x] `OutboxSyncScheduler`（`@Scheduled` 5s 间隔，claim → 回查 MySQL → ES upsert/delete）
- [x] `FileInfoService.updateStatusWithOutbox()`（`@Transactional` 保证 file_info + outbox_event 原子写入）
- [x] `FileUploadConsumer` 移除 ES 直接调用，改用 outbox
- [x] `FileProcessorApplication` 启用 `@EnableScheduling`
- [x] `OutboxEventServiceTest`（12 tests）
- [x] 总测试数：35（23 原有 + 12 新增）

### 一致性语义

**至少一次执行 + 幂等处理 + 最终一致性**。ES 文档以 fileId 作为 `_id`，重复 index 操作为 upsert，不会产生重复文档。调度器可能因进程崩溃而重复处理同一事件，但 ES 结果始终正确。

### 相关文档

- [search-consistency.md](./search-consistency.md) — ES 一致性专题（方案对比、架构设计、一致性保证、FAQ）
- [init-outbox_event.sql](./sql/init-outbox_event.sql) — outbox_event 完整 DDL

---

## Phase 5 · 文件解析内存风险控制与性能验证（✅ 已完成）

**时间**：2026-07-16
**目标**：为 TXT/PDF/DOCX 解析链路增加 OOM 保护，补齐损坏文件测试，提供压测模板。

### 功能清单

- [x] TXT 流式解析（`BufferedReader` + char buffer + `MAX_TEXT_BYTES` + `MAX_EXTRACTED_CHARS` 截断）
- [x] PDF 文件大小门禁（`MAX_PDF_BYTES` 20MB）+ 页数门禁（`MAX_PDF_PAGES` 500）+ `BoundedWriter` 字符截断
- [x] DOCX 文件大小门禁（`MAX_DOCX_BYTES` 20MB）+ 段落数门禁（`MAX_PARAGRAPHS` 10000）+ 字符截断
- [x] 所有解析失败返回安全简短消息（不泄露异常细节）
- [x] `FileContentExtractorTest`（20 tests，覆盖正常/空/超限/损坏/不支持/null）
- [x] 总测试数：55（35 原有 + 20 新增）
- [x] 性能报告模板（`docs/performance-report.md` — 仅模板，不虚构数据）
- [x] 压测指南（`docs/perf/README.md`）
- [ ] 用户执行压测并填写真实数据（非代码任务）

### 已知边界

- PDF 文本截断通过 `BoundedWriter` + `writeText()` 实现，避免全量 String 后再截断。但 PDFBox 仍会完整遍历页面对象树 → 文件大小 + 页数门禁配合控制。
- 解析失败时 `file_info.status` 保持原有语义，错误原因记录在日志而非 DB。后续可优化为独立 `error_reason` 字段。
- 不实现 PDF 解析超时（`Future.cancel()` 无法安全中断 PDFBox 底层 IO）。
- 不引入新依赖（PDFBox + POI 已在 Phase 0 引入）。

---

## Backlog · AI / RAG 扩展（💡 未来规划）

**状态**：不列入近期实现计划。保留在 README 和 roadmap 中作为未来方向展示。

### 规划功能

- [ ] 文本切片服务（按段落/句子/固定长度 + overlap）
- [ ] `text_chunk` 表 + Mapper
- [ ] 关键词匹配版 QA（POST `/api/qa/ask`）
- [ ] SSE 流式问答（GET `/api/qa/ask/stream`）
- [ ] 切片查询（GET `/api/qa/chunks/{fileId}`）

### 预留扩展（不做实现计划）

- LLM 接入（OpenAI / 国内大模型）
- Embedding + Milvus 向量库
- Reranker 重排序
- 多轮对话上下文

> ⚠️ 当前项目定位为 Java 后端实习 / 平台后端 / 搜索后端，暂不引入 AI/RAG 依赖。以上全部为 Future Backlog。

---

## 里程碑时间线

```
2026-07-10  ████████  Phase 0 · 基础平台 ✅
2026-07-14  ████████  Phase 1 · 可靠消息 ✅
2026-07-15  ████████  Phase 2 · 大文件上传 ✅
2026-07-15  ████████  Phase 3 · 代码质量加固 ✅
2026-07-16  ████████  Phase 4 · 数据一致性 ✅
2026-07/08  ████████  Phase 5 · 性能优化 ✅
2026-09+     ░░░░░░░░  Backlog · RAG/AI 💡
```

> Phase 4 采用 Transactional Outbox 替代 Canal，轻量实现最终一致性。

---

## 变更记录

| 日期 | 说明 |
|------|------|
| 2026-07-16 | Phase 5 代码部分完成：解析 OOM 保护（TXT 流式 + PDF/DOCX 门禁 + 20 测试），55 tests |
| 2026-07-16 | Phase 4 → ✅：Transactional Outbox Pattern 实现 MySQL-ES 最终一致性（35 tests） |
| 2026-07-15 | Phase 1 → ✅、Phase 2 → ✅、Phase 3 → ✅；Phase 4 调整为 Canal 一致性；Phase 5 调整为 JVM 优化；原 AI/RAG → Backlog |
| 2026-07-14 | 初版，Phase 0 标记已完成 |
