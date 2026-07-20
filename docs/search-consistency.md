# ES 搜索一致性设计

> Smart File Processor · 数据一致性专题文档
> 最后更新：2026-07-16

---

## 问题定义

文件元数据和解析结果存储在 MySQL（`file_info` 表），同时需要在 Elasticsearch 中建立全文索引（`file_index`）。这两个存储系统没有分布式事务支持，直接同步双写会导致数据不一致。

### 不一致场景

| 场景 | MySQL | ES | 后果 |
|------|-------|----|------|
| ES 写入失败 | status=2（成功） | 文档不存在 | 搜索不到该文件 |
| ES 写入成功但客户端超时 | status=2 | 文档存在 | 正常（幂等） |
| MySQL 写入失败 | 回滚 | 已写入（无法回滚） | ES 有脏数据 |
| 进程崩溃 | 已提交 | 未写入 | 搜索不到该文件 |

---

## 方案对比

### 方案 A：同步双写（Phase 0-3 使用，已替换）

```
Consumer: updateMySQL() → esClient.index() → ACK
```

- **问题**：两步无事务保证。MySQL 成功 + ES 失败 = 不一致。靠 Spring Retry（最多 3 次）兜底但不可靠。
- **结论**：❌ 不适用于生产。

### 方案 B：Canal + MySQL binlog

```
MySQL binlog → Canal Server → Canal Client → ES
```

- **优点**：完全解耦，业务代码零侵入；binlog 天然有序
- **缺点**：额外部署 Canal Server（运维成本）；binlog 格式兼容风险（MySQL 版本升级）；解析 `MEDIUMTEXT` 类型的大 content 字段有性能问题
- **结论**：❌ 项目规模不适合引入 CDC 中间件。

### 方案 C：Transactional Outbox Pattern ✅

```
Business Tx:  UPDATE file_info + INSERT outbox_event → ACK
Scheduler:    SELECT outbox_event → claim → 回查 MySQL → ES upsert → mark SUCCESS/FAILED
```

- **优点**：仅依赖 MySQL 本地事务；业务代码侵入小；无额外中间件；轻量、可观测
- **缺点**：ES 同步有延迟（最多 5s schedule interval）；需维护 outbox_event 表
- **结论**：✅ 最适合当前项目规模。

---

## 架构设计

```
┌────────────────────────────────────────────────────────────┐
│                    FileUploadConsumer                       │
│                                                             │
│  handleFileUpload() {                                       │
│    updateParseStatus(PARSING)                               │
│    ossService.uploadFile(...)                               │
│    content = extractor.extractContent(...)                  │
│    ┌─────────────────────────────┐                          │
│    │ @Transactional              │                          │
│    │ fileInfoService             │                          │
│    │   .updateStatusWithOutbox(  │                          │
│    │     id, 2, content, url)    │                          │
│    │   ├─ UPDATE file_info       │                          │
│    │   └─ INSERT outbox_event    │ ← 同一事务               │
│    └─────────────────────────────┘                          │
│    channel.basicAck()  ← 事务成功即 ACK                     │
│  }                                                          │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼ (异步，@Scheduled 5s)
┌────────────────────────────────────────────────────────────┐
│                   OutboxSyncScheduler                       │
│                                                             │
│  syncToElasticsearch() {                                    │
│    events = outboxEventService.claimEvents(lockedBy, 20)    │
│      └─ UPDATE ... SET status=PROCESSING WHERE PENDING/...  │
│                                                             │
│    for each event:                                          │
│      fileInfo = mysql.findById(event.aggregateId)           │
│      es.index(_id=fileId, doc)  ← 幂等 upsert               │
│      ├─ success → markSuccess()                             │
│      └─ fail → markFailed() → next_retry_at = NOW() + 退避   │
│  }                                                          │
└────────────────────────────────────────────────────────────┘
```

---

## 核心组件

### outbox_event 表

| 字段 | 类型 | 说明 |
|------|------|------|
| `event_id` | VARCHAR(64) | 事件唯一 ID（UUID 去横线），全局幂等 |
| `aggregate_type` | VARCHAR(50) | 聚合类型，当前固定 `FILE_INFO` |
| `aggregate_id` | VARCHAR(64) | 聚合 ID，即 `fileId` |
| `event_type` | VARCHAR(50) | 事件类型：`FILE_INDEX_UPSERT` |
| `payload` | TEXT | 最小事件载荷（只存 fileId + traceId，**不存大段 content**） |
| `status` | VARCHAR(20) | `PENDING` → `PROCESSING` → `SUCCESS` / `FAILED` |
| `retry_count` | INT | 已重试次数 |
| `max_retries` | INT | 最大重试次数（默认 5） |
| `next_retry_at` | DATETIME | 下次可重试时间 |
| `locked_at` | DATETIME | 抢占时间 |
| `locked_by` | VARCHAR(64) | 抢占者标识（hostname:thread） |

**幂等机制**：
- `UNIQUE KEY uk_aggregate_event (aggregate_type, aggregate_id, event_type)` 保证同一文件同一事件只有一条
- `INSERT ... ON DUPLICATE KEY UPDATE` 将已有事件重置为 PENDING / retry_count=0
- ES 文档以 `fileId` 作为 `_id`，index 操作为 upsert

### 事件生命周期

```
PENDING ──claim──▶ PROCESSING ──success──▶ SUCCESS
    ▲                  │
    │                  │ fail
    │                  ▼
    └──retry── FAILED (retry_count < max_retries, next_retry_at 到期)
                          │
                          │ retry_count >= max_retries
                          ▼
                      FAILED (最终失败，等待人工补偿)
```

### claim 抢占逻辑

```sql
-- 原子抢占 PENDING 或可重试的 FAILED 事件
UPDATE outbox_event SET
    status = 'PROCESSING',
    locked_at = NOW(),
    locked_by = #{lockedBy}
WHERE id IN (
    SELECT id FROM (
        SELECT id FROM outbox_event
        WHERE status = 'PENDING'
           OR (status = 'FAILED'
               AND retry_count < max_retries
               AND next_retry_at <= NOW())
        ORDER BY next_retry_at ASC
        LIMIT #{batchSize}
    ) AS tmp
)
```

- 只有 UPDATE affected rows > 0 的事件才被处理
- 多实例安全：同一事件不会被两个实例同时抢占
- 如果处理进程崩溃，`PROCESSING` 事件会在超时后被重新调度（可通过 locked_at 超时检测增强，当前版本未实现，留作后续优化）

### 重试退避策略

```
next_retry_at = NOW() + 5s × 2^retryCount，上限 10 分钟

retry 0 → 5s
retry 1 → 10s
retry 2 → 20s
retry 3 → 40s
retry 4 → 80s
retry 5 → 160s
...
retry 7+ → 上限 600s（10 分钟）
```

---

## 一致性保证

### 正常路径

1. Consumer 处理成功 → `@Transactional` 原子写入 `file_info` + `outbox_event` → ACK
2. Scheduler 扫描到 PENDING 事件 → claim → 回查 MySQL 最新数据 → ES upsert → mark SUCCESS
3. **一致性**：MySQL 已提交 + ES 已同步 ✅

### 异常路径 1：ES 同步失败

1. Scheduler 处理事件时 ES 不可用
2. `markFailed()` → `retry_count = 1`, `next_retry_at = NOW() + 5s`
3. 5 秒后 Scheduler 重新 claim 该事件 → 再次尝试
4. 最多重试 5 次，每次退避翻倍
5. **最终一致性**：ES 恢复后可同步 ✅

### 异常路径 2：Scheduler 进程崩溃

1. Scheduler 已 claim 事件（status=PROCESSING）但未完成
2. 进程崩溃，locked_at 保留
3. **当前版本**：事件保持 PROCESSING，不会被重新 claim（不做超时恢复）
4. **后续增强**：Scheduler 启动时扫描 `PROCESSING + locked_at < NOW() - 5min` 的事件并重置为 PENDING

### 异常路径 3：Consumer 事务失败

1. `updateStatusWithOutbox()` 内数据库操作失败 → 事务回滚
2. `file_info` 和 `outbox_event` 均不提交
3. Consumer 抛 RuntimeException → Spring Retry → 最多 3 次
4. **一致性**：MySQL 和 outbox 保持一致的旧状态 ✅

---

## 一致性语义

**本实现提供的是：至少一次执行 + 幂等处理 + 最终一致性。**

- **不是 exactly-once**：Scheduler 可能因为崩溃或网络超时而重复处理同一事件
- **幂等处理**：ES `_id = fileId` 的 index 是 upsert 操作，重复执行不会产生重复文档
- **最终一致性**：ES 同步有最多 5s 延迟（schedule interval），但最终会收敛到与 MySQL 一致

---

## 监控与补偿

### 可观测性

- Scheduler 日志：每次 claim 数量、各事件处理结果
- `outbox_event` 表直接查询：`SELECT * FROM outbox_event WHERE status = 'FAILED'`
- 与 `file_info` 关联查询：`SELECT f.*, e.status FROM file_info f JOIN outbox_event e ON e.aggregate_id = f.id`

### 人工补偿

- 长期 FAILED 的事件需人工介入：检查 ES 是否可用 → 手动重置为 PENDING（或调用重新创建事件）
- 未来可增加管理接口：`POST /api/admin/es/reindex`（全量重建）和 `POST /api/admin/es/sync/{fileId}`（单文件重同步）

---

## FAQ

### Q: 为什么 payload 不存文件内容？

A: 文件解析后的 `content` 字段可能非常大（几十 KB 甚至 MB 级别），存入 outbox_event 会导致：
- 表膨胀，影响 claim 查询性能
- 长事务中写入大量数据拖慢 Consumer

解决方案：Scheduler 处理事件时通过 `aggregate_id`（即 `fileId`）回查 `file_info` 表获取最新 content。这避免了 outbox_event 存储大字段，且保证 ES 始终同步到最新的 MySQL 数据。

### Q: Scheduler 5 秒间隔合理吗？

A: 5 秒是可配置的（`outbox.sync.interval-ms`），对于文件检索场景（非实时搜索）足够。如果需要更低的延迟，可以调整到 1 秒。但需注意 ES 集群的写入负载。

### Q: 如何确保幂等？

A: 两层幂等保证：
1. DB 层：`INSERT ... ON DUPLICATE KEY UPDATE` → event_id 唯一 + (aggregate_type, aggregate_id, event_type) 唯一
2. ES 层：`_id = fileId` → index 是 upsert，重复执行不产生重复文档

### Q: 和 Canal 方案比有什么优劣？

| 维度 | Transactional Outbox | Canal |
|------|---------------------|-------|
| 部署复杂度 | 无需额外组件 | 需 Canal Server |
| 延迟 | 最多 5s（可调） | 秒级 |
| 运维成本 | 仅 MySQL 表 | Canal + ZooKeeper |
| 代码侵入 | Consumer 改一行 | 零侵入 |
| 适用场景 | 中小规模 | 大规模 |

本项目选择 Outbox 的核心原因：**当前规模不需要 CDC 中间件，MySQL 本地事务足够可靠，维护成本最低。**

---

## 相关文件

| 文件 | 说明 |
|------|------|
| `docs/sql/init-outbox_event.sql` | DDL |
| `entity/OutboxEvent.java` | 实体 |
| `enums/OutboxEventStatus.java` | 状态枚举 |
| `enums/OutboxEventType.java` | 事件类型枚举 |
| `mapper/OutboxEventMapper.java` | 持久层（含 claim SQL） |
| `service/OutboxEventService.java` | 事件管理 |
| `service/OutboxSyncScheduler.java` | 调度任务 |
| `service/FileInfoService.java` | `updateStatusWithOutbox()` |
| `service/FileUploadConsumer.java` | 消费者（移除直接 ES 调用） |

---

## 变更记录

| 日期 | 说明 |
|------|------|
| 2026-07-16 | Phase 4 完成，Transactional Outbox 上线 |
