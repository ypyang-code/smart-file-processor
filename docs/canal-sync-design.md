# Phase 3: Canal 数据一致性方案设计

> **项目**：Smart File Processor
> **阶段**：Phase 3 — MySQL ↔ Elasticsearch 数据一致性
> **文档定位**：Phase 3 实现前的正式设计文档，基于前置审计结论
> **创建日期**：2026-07-15
> **状态**：设计中（闭环 1 完成，闭环 2-7 待实现）

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [当前双写链路审计](#2-当前双写链路审计)
3. [当前一致性风险](#3-当前一致性风险)
4. [Canal 可行性判断](#4-canal-可行性判断)
5. [方案对比](#5-方案对比)
6. [推荐架构](#6-推荐架构)
7. [同步规则设计](#7-同步规则设计)
8. [Phase 3 闭环拆分](#8-phase-3-闭环拆分)
9. [预计修改文件清单](#9-预计修改文件清单)
10. [风险与边界](#10-风险与边界)
11. [验收标准](#11-验收标准)

---

## 1. 背景与目标

### 1.1 前置阶段成果

| 阶段 | 完成内容 | 对 Phase 3 的意义 |
|:--|:--|:--|
| **Phase 1** | RabbitMQ 可靠性增强（MANUAL ACK + Spring Retry + DLX/DLQ + Producer Confirm + 消息持久化） | MQ 消息投递链已可靠——消息不会丢、失败可追溯。Phase 3 在此基础上解决更上层的问题 |
| **Phase 2** | 大文件分片上传（MD5 秒传 + 分片上传 + 流式合并 + MQ 触发解析） | 上传链已可靠——文件可安全到达 Consumer。Phase 3 解决 Consumer 处理完成后的数据一致性问题 |

### 1.2 Phase 3 要解决的问题

Phase 1 和 Phase 2 确保了**消息和文件的可靠性**，但在 Consumer 内部，处理成功后的数据写入仍然存在隐患：

```
Consumer 处理成功
  ├── [1] MySQL 写入（@Transactional，有事务保证）  ← ✅ Phase 1 保障 Consumer 可靠消费
  ├── [2] ES 写入（手动调用，无事务，无补偿）        ← ❌ 与 [1] 不在同一事务
  └── [3] basicAck
```

**问题**：MySQL 写入成功 + ES 写入失败 → MySQL 显示"解析成功"，但 ES 中没有文档 → **用户搜不到已上传的文件**。

这是项目当前最大的遗留数据一致性风险。

### 1.3 Phase 3 目标

> **MySQL 成为唯一事实源（source of truth），Elasticsearch 作为派生搜索视图（derived search view），通过 Canal 监听 MySQL binlog 实现最终一致性同步。**

**明确边界**：

- ✅ Canal 解决的是 **MySQL 已提交之后到 ES 的同步问题**
- ❌ Canal 不解决 MQ 消息投递问题（Phase 1 已解决）
- ❌ Canal 不解决 MySQL 写入前的业务逻辑问题（Consumer 内部已有事务保证）
- ❌ Canal 不等于强一致（binlog 消费有 ms 级延迟，是最终一致方案）

---

## 2. 当前双写链路审计

### 2.1 MySQL 写入位置

**`FileUploadConsumer.java` L91**（成功路径）：

```java
// 3. 更新数据库状态为已完成
fileInfoService.updateStatus(fileId, 2, content, ossUrl);
```

**`FileInfoService.updateStatus()`** 是 `@Transactional` 方法，内部两步：

```java
@Transactional
public void updateStatus(Long id, Integer status, String content, String ossUrl) {
    // A. 更新 status, content, oss_url
    fileInfoMapper.update(fileInfo);
    // B. 同步更新 parseStatus
    if (status == 2) {
        fileInfoMapper.updateParseStatus(id, "PARSE_SUCCESS");
    } else if (status == 3) {
        fileInfoMapper.updateParseStatus(id, "PARSE_FAILED");
    }
}
```

### 2.2 ES 写入位置

**`FileUploadConsumer.java` L93-106**：

```java
// 4. 索引到 Elasticsearch
FileDocument doc = new FileDocument();
doc.setId(fileInfo.getId());
doc.setFileName(fileName);
doc.setContent(content);
doc.setFileType(fileType);
doc.setOssUrl(ossUrl);

elasticsearchClient.index(i -> i
        .index("file_index")
        .id(String.valueOf(doc.getId()))
        .document(doc)
);
```

ES 写入在 MySQL `updateStatus()` **之后**、`basicAck` **之前**。不在 `@Transactional` 范围内。

### 2.3 成功路径

```
Consumer 收到消息
  ├── updateParseStatus(PARSING)
  ├── OSS 上传
  ├── 文本提取（PDFBox / POI）
  ├── updateStatus(fileId, 2, content, ossUrl)     ← @Transactional
  │     ├── UPDATE file_info SET status=2, content=..., oss_url=...
  │     └── UPDATE file_info SET parse_status='PARSE_SUCCESS'
  ├── elasticsearchClient.index("file_index", doc)   ← 非事务
  └── channel.basicAck()
```

### 2.4 失败路径

```
Consumer 异常
  ├── catch (FileNotFoundException)
  │     ├── updateStatus(fileId, 3, "文件不存在: ...", null)
  │     │     ├── UPDATE file_info SET status=3, content="文件不存在: ..."
  │     │     └── UPDATE file_info SET parse_status='PARSE_FAILED'
  │     └── basicNack(requeue=false) → DLQ → DeadLetterConsumer
  │           └── updateStatus(fileId, 3, "消息进入死信队列: ...", null)
  │
  └── catch (Exception) → Spring Retry (最多 3 次)
        ├── 重试成功 → basicAck
        └── 重试耗尽 → Container basicNack(requeue=false) → DLQ
```

**关键观察**：失败路径的 ES 不会被写入（代码逻辑正确），但**没有任何机制从 ES 中删除旧文档**——如果同一个 fileId 之前曾被成功索引过，之后重新上传失败，ES 中仍保留旧文档。

### 2.5 分片合并路径

```
ChunkUploadService.mergeChunks()
  ├── 流式合并 .part 文件
  ├── MD5 校验
  ├── completeMerge(id, storagePath)               ← @Transactional
  │     ├── UPDATE file_info SET storage_path=..., upload_status='UPLOADED', merge_time=...
  │     └── UPDATE file_info SET parse_status='WAITING_PARSE'
  ├── fileUploadProducer.sendUploadTask(msg)       ← 发送 MQ（Phase 1 可靠链路）
  └── 清理 .part 分片文件

→ Consumer 消费 → 同上成功/失败路径
```

**关键观察**：分片合并成功后 `parse_status=WAITING_PARSE`，内容尚未提取。此阶段不应出现在搜索结果中。

### 2.6 当前 ES 索引结构

| 项目 | 值 |
|:--|:--|
| **索引名称** | `file_index`（硬编码字符串） |
| **Document ID** | `String.valueOf(fileInfo.getId())` |
| **字段** | `id` (long), `fileName` (text), `content` (text), `fileType` (keyword), `ossUrl` (keyword) |
| **Entity** | `FileDocument.java`（5 字段） |
| **Mapping 创建** | README 中手动 `curl -X PUT` 命令，无 Java 代码初始化 |
| **索引重建** | 不存在。无 reindex 接口、无 mapping 版本管理、无别名切换 |

### 2.7 SearchController 查询方式

```java
// 1. ES multiMatch 搜索 fileName + content
elasticsearchClient.search(s -> s.index("file_index")
    .query(q -> q.multiMatch(m -> m.fields("fileName", "content").query(keyword))))

// 2. ES 返回 FileDocument.id → 逐条回查 MySQL
response.hits().hits().forEach(hit -> {
    FileInfo fileInfo = fileInfoService.getById(doc.getId());
    results.add(fileInfo);
});
```

**N+1 查询问题**：ES 返回 N 条结果 → N 次 MySQL 查询（当前数据量小，不明显，暂不作为 Phase 3 重点）。

### 2.8 content 字段存储分析

| 字段 | MySQL (`file_info`) | ES (`file_index`) | 用途 |
|:--|:--|:--|:--|
| `content` | ✅ TEXT | ✅ text | MySQL：API 返回（`GET /api/file/{id}`）；ES：全文搜索 |
| `fileName` | ✅ VARCHAR | ✅ text | MySQL：列表展示；ES：搜索 |
| `ossUrl` | ✅ VARCHAR | ✅ keyword | MySQL：文件下载；ES：暂不用于搜索 |
| `fileType` | ✅ VARCHAR | ✅ keyword | MySQL：分类过滤；ES：暂不用于过滤 |
| `status` | ✅ INT | ❌ 无 | 仅 MySQL |
| `parseStatus` | ✅ VARCHAR | ❌ 无 | 仅 MySQL |
| `uploadStatus` | ✅ VARCHAR | ❌ 无 | 仅 MySQL |

**关键发现**：`content` **两边都存**，是全文搜索的必要字段，Canal 同步时必须传输。`content` 作为 TEXT 字段可能较大（几十 KB 到几 MB），是 Canal 同步的性能验证重点。

---

## 3. 当前一致性风险

### 风险矩阵

| # | 场景 | 触发条件 | 后果 | 严重度 | 是否可恢复 |
|:--|:--|:--|:--|:--|:--|
| 1 | **MySQL 成功 + ES 失败** | `updateStatus()` 已提交 → `elasticsearchClient.index()` 抛 IOException | MySQL status=2, PARSE_SUCCESS；ES 无文档 → **用户搜不到文件** | 🔴 高 | ❌ 无自动恢复 |
| 2 | **Consumer 在 MySQL 与 ES 之间崩溃** | `updateStatus()` 已提交 → 💥 进程崩溃 → ES 未写入 | 同上 → 文件永久对用户不可见 | 🔴 高 | ❌ 无自动恢复 |
| 3 | **PARSE_FAILED 后 ES 仍有旧文档** | 文件重新处理失败（status 3 → 3），但上次成功时 ES 已索引 | ES 返回"已失败"的旧数据 → 用户搜到但点开是失败状态 | 🟡 中 | ❌ 需手动清理 |
| 4 | **分片合并成功 + MQ 发送失败** | `completeMerge()` 已提交 → `sendUploadTask()` 抛 AmqpException | MySQL UPLOADED + WAITING_PARSE；ES 无文档 → 用户看到合并成功但搜不到 | 🟡 中 | ⚠️ Phase 1 已通过日志暴露 |
| 5 | **Consumer 重试导致 ES 重复写入** | 上次已写 ES 但 basicAck 失败 → 重新处理 → 再次写 ES | ES 覆盖写（相同 document id），最终一致但浪费 OSS 传输和文本提取 | 🟢 低 | ✅ 最终一致，仅浪费资源 |
| 6 | **parseStatus 变更但 ES 无感知** | WAITING_PARSE → PARSING → PARSE_SUCCESS → 只有最终成功时才写 ES | 中间状态不影响搜索——但 PARSE_FAILED 时如果没有 DELETE 逻辑，ES 中残留 | 🟡 中 | ❌ 需逻辑修复 |
| 7 | **SearchController 依赖 ES 结果** | ES 漏写 → `multiMatch` 查不到 → results 为空 | **用户直接感知**：上传成功但搜不到 | 🔴 高 | ❌ 无降级 |

### 根因总结

**MySQL 写入和 ES 写入是两次独立的、非原子的操作**。第一阶段（MySQL 写入）由 `@Transactional` 保证原子性，第二阶段（ES 写入）在事务之外，没有任何保障机制。中间任何故障（网络、进程、ES 不可用）都会导致永久性的数据不一致。

Phase 1 已经解决了"MQ 消息丢不丢"的问题，Phase 3 需要解决"数据写入了 MySQL 但 ES 未同步"的问题。

---

## 4. Canal 可行性判断

### 前置条件检查

| # | 条件 | 现状 | 结论 |
|:--|:--|:--|:--|
| 1 | MySQL 版本支持 binlog | MySQL 8.0，`log_bin` 默认 ON | ✅ |
| 2 | `binlog_format=ROW` | MySQL 8.0 默认 ROW 格式 | ✅ 需确认 |
| 3 | 表有稳定主键 | `file_info.id BIGINT AUTO_INCREMENT PRIMARY KEY` | ✅ |
| 4 | 主键可直接作为 ES document id | 当前 ES document id = `String.valueOf(file_info.id)` | ✅ |
| 5 | Canal Server 可部署 | Docker `canal/canal-server:latest` | ✅ |
| 6 | MySQL REPLICATION 权限 | Docker 本地开发环境默认 root 具备 | ✅ 云数据库需确认 |

### 同步策略设计

| 场景 | MySQL 事件 | parse_status 条件 | ES 操作 |
|:--|:--|:--|:--|
| 文件解析成功 | INSERT / UPDATE | `parse_status = PARSE_SUCCESS` | **UPSERT** |
| 文件解析失败 | UPDATE | `parse_status = PARSE_FAILED` | **DELETE** |
| 文件删除 | DELETE | — | **DELETE** |
| 等待解析 | INSERT / UPDATE | `parse_status = WAITING_PARSE` | **跳过** |
| 解析中 | UPDATE | `parse_status = PARSING` | **跳过** |
| 未解析 | INSERT | `parse_status = NOT_PARSED` | **跳过** |
| 仅上传状态变更 | UPDATE | `upload_status` 变化但 `parse_status` 未 PARSE_SUCCESS | **跳过** |

### 关键风险与缓解

| 风险 | 影响 | 缓解措施 |
|:--|:--|:--|
| **content 大字段通过 binlog 传输** | Canal 网络传输和 Client 内存压力 | 实测后决定是否 column filtering。当前 content 同步频率极低（每条记录生命周期内最多一次），预计可控 |
| **存量数据无 binlog 记录** | 已 PARSE_SUCCESS 的历史数据无法通过 Canal 同步 | 提供 `POST /api/admin/es/reindex` 全量同步接口 |
| **Canal Client 挂机期间数据无同步** | 宕机期间的变更遗漏 | binlog 持久化 + Client 重启后从上次位点恢复 |
| **Canal 同步延迟** | 用户上传后短暂搜不到 | 延迟通常 < 1s，可接受。对账任务兜底 |
| **ES mapping 变更** | 新增字段或修改分词器需要重建索引 | 使用 ES 索引别名 + 零停机 Reindex API |

### 可行性结论

**完全可行。** MySQL 8.0 + ROW binlog + 单表主键设计满足 Canal 的全部前置条件。唯一需要额外准备的是 Docker Canal Server（约 200MB 内存）。content 大字段需实测确认性能边界。

---

## 5. 方案对比

### 方案 A：Canal 直接监听 file_info，同步 ES（推荐）

**思路**：
- `FileUploadConsumer` 删除 `elasticsearchClient.index()` 调用（7 行代码）
- 部署 Canal Server 监听 `file_processor.file_info` 的 binlog
- Canal Client（Spring Boot 内嵌）消费 binlog 事件
- `FileInfoEventHandler` 按 `parse_status` 过滤：PARSE_SUCCESS → UPSERT ES；PARSE_FAILED → DELETE ES
- `EsReconciliationService` 定时对账兜底

| 维度 | 评价 |
|:--|:--|
| Consumer 代码改动 | ✅ 仅删除 7 行，不新增逻辑 |
| 新增中间件 | ⚠️ Canal Server (Docker, ~200MB) |
| 新增代码量 | ~400 行（Canal Client + EventHandler + SyncService + Reconciliation） |
| 实时性 | ✅ 近实时（binlog 延迟 < 1s） |
| 运维复杂度 | ⚠️ 需维护 Canal Server 进程和 binlog 位点 |
| 面试区分度 | ⭐⭐⭐⭐⭐ |
| RAG 兼容性 | ✅ `text_chunk` 表可直接复用 Canal 同步通道 |

### 方案 B：Outbox / es_sync_log 表

**思路**：
- 业务代码在同一个 MySQL 事务中写入 `file_info` 和 `es_sync_log`（状态=PENDING）
- 定时任务（`@Scheduled`）或异步服务轮询 `es_sync_log` 表
- 消费成功后标记 SUCCESS，失败后标记 FAILED 并重试

| 维度 | 评价 |
|:--|:--|
| Consumer 代码改动 | ❌ 需在 `updateStatus()` 事务中增加 INSERT `es_sync_log` |
| 新增中间件 | ✅ 无需额外中间件 |
| 新增代码量 | ~300 行（es_sync_log 表 + Mapper + SyncService + 定时任务） |
| 实时性 | ⚠️ 取决于轮询间隔（通常 5-30s） |
| 运维复杂度 | ✅ 纯应用层，无额外进程 |
| 面试区分度 | ⭐⭐⭐ |
| RAG 兼容性 | ⚠️ 每张新表需要单独的 Outbox 机制 |

### 方案 C：保留 Consumer 直接写 ES，仅加对账补偿

**思路**：
- Consumer 不做任何改动
- 新增 `EsReconciliationService` 定时（如每天凌晨）对比 MySQL ↔ ES
- 发现差异记录日志或自动修复

| 维度 | 评价 |
|:--|:--|
| Consumer 代码改动 | ✅ 零改动 |
| 新增中间件 | ✅ 无需额外中间件 |
| 新增代码量 | ✅ ~150 行（仅对账任务） |
| 实时性 | ❌ 取决于对账间隔（通常小时级） |
| 运维复杂度 | ✅ 最低 |
| 面试区分度 | ⭐⭐ |
| RAG 兼容性 | ❌ 未从根上解决双写问题 |

### 综合对比

| 维度 | 方案 A：Canal | 方案 B：Outbox | 方案 C：仅补偿 |
|:--|:--|:--|:--|
| 根本解决双写问题 | ✅ 是（删除 Consumer 写 ES） | ✅ 是（改为事务中写 Outbox） | ❌ 否（保留双写） |
| 实时性 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐ |
| Consumer 改动量 | 删除 7 行 | 修改 ~20 行 | 0 行 |
| 新增代码量 | ~400 行 | ~300 行 | ~150 行 |
| 新增中间件 | Canal Server | 无 | 无 |
| 面试技术含量 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ |
| RAG 可扩展性 | ✅ 天然支持多表 | ⚠️ 需额外开发 | ❌ 不解决 |
| **推荐** | ✅ **首选** | 备选 | 不推荐 |

### 推荐决策

**优先采用方案 A：Canal 直接同步 + 对账补偿。**

如果因环境限制无法部署 Canal Server（如云数据库不开放 REPLICATION 权限、内网策略限制），则退化为方案 B（Outbox 表）。

方案 C 仅适合作为方案 A 或 B 实现前的**临时过渡**，不适合作为长期方案。

---

## 6. 推荐架构

### 6.1 目标架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        业务层（简化后）                               │
│                                                                      │
│  FileUploadConsumer.handleFileUpload(msg)                           │
│  ├── OSS 上传                                                        │
│  ├── 文本提取（PDFBox / POI）                                        │
│  ├── updateStatus(2, content, ossUrl)  → MySQL (@Transactional)     │
│  │     ├── UPDATE file_info SET status=2, content=..., oss_url=...   │
│  │     └── UPDATE file_info SET parse_status='PARSE_SUCCESS'         │
│  ├── ❌ 删除: elasticsearchClient.index()  ← Phase 3 移除            │
│  └── channel.basicAck()                                             │
│                                                                      │
│  SearchController.search(keyword)                                   │
│  ├── ES multiMatch("fileName", "content")                           │
│  └── 逐条回查 MySQL (getById)                                        │
│  （不变 — ES 查询方式无变化）                                         │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ MySQL binlog (ROW format)
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        同步层（新增）                                  │
│                                                                      │
│  ┌──────────────────────────────────────────────┐                   │
│  │  Canal Server (Docker)                        │                   │
│  │  - 伪装成 MySQL Slave                          │                   │
│  │  - 订阅 file_processor.file_info               │                   │
│  │  - 推送 binlog 事件到 Canal Client              │                   │
│  └──────────────────┬───────────────────────────┘                   │
│                     │                                                │
│                     ▼                                                │
│  ┌──────────────────────────────────────────────┐                   │
│  │  Canal Client (Spring Boot 内嵌)               │                   │
│  │  - CanalConnector 连接管理 + 断线重连           │                   │
│  │  - batchSize=1000, 批量消费                     │                   │
│  └──────────────────┬───────────────────────────┘                   │
│                     │                                                │
│                     ▼                                                │
│  ┌──────────────────────────────────────────────┐                   │
│  │  FileInfoEventHandler                         │                   │
│  │  - 解析 binlog Entry → INSERT/UPDATE/DELETE   │                   │
│  │  - 提取变更后的行数据                           │                   │
│  │  - 按 parse_status 过滤                        │                   │
│  │    ├── PARSE_SUCCESS → 调用 EsSyncService      │                   │
│  │    ├── PARSE_FAILED  → 调用 EsSyncService      │                   │
│  │    └── 其他状态      → 跳过                     │                   │
│  └──────────────────┬───────────────────────────┘                   │
│                     │                                                │
│                     ▼                                                │
│  ┌──────────────────────────────────────────────┐                   │
│  │  EsSyncService                                │                   │
│  │  - index(fileDocument)    → ES UPSERT         │                   │
│  │  - delete(fileId)         → ES DELETE         │                   │
│  │  - 异常 → es_sync_log (FAILED)                │                   │
│  └──────────────────┬───────────────────────────┘                   │
│                     │                                                │
│                     ▼                                                │
│  ┌──────────────────────────────────────────────┐                   │
│  │  Elasticsearch file_index                     │                   │
│  │  (搜索视图，派生自 MySQL)                       │                   │
│  └──────────────────────────────────────────────┘                   │
│                                                                      │
│  ┌──────────────────────────────────────────────┐                   │
│  │  EsReconciliationService (@Scheduled 每天凌晨) │                   │
│  │  - 对比 MySQL (PARSE_SUCCESS) ↔ ES            │                   │
│  │  - 差异 → 自动修复 + 写日志                     │                   │
│  │  - 手动触发: POST /api/admin/es/reconcile     │                   │
│  └──────────────────────────────────────────────┘                   │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 核心设计原则

1. **MySQL 是唯一事实源（source of truth）**。Consumer 只写 MySQL，不再直接写 ES。
2. **ES 是派生搜索视图（derived search view）**。其数据完全由 binlog 事件计算生成。
3. **SearchController 暂不改动**。ES 查询方式无变化，只是数据来源从"Consumer 手动写"变成了"Canal 自动同步"。
4. **FileDocument 暂不改动**。5 个字段不变。后续可考虑新增 `parseStatus` 字段用于搜索过滤——但当前 `SearchController` 只搜所有可搜索的文件，不需要此字段。
5. **为 RAG 预留扩展点**。后续 `text_chunk` 表可新增 Canal 订阅规则，复用同一套同步基础设施。

---

## 7. 同步规则设计

### 7.1 INSERT 事件

```
收到 file_info INSERT 事件
  │
  ├── afterRow.parse_status = 'PARSE_SUCCESS' → ES UPSERT (index)
  │     └── document: { id, fileName, content, fileType, ossUrl }
  │
  ├── afterRow.parse_status = 'PARSE_FAILED' → 跳过（不写入 ES）
  ├── afterRow.parse_status = 'WAITING_PARSE' → 跳过
  ├── afterRow.parse_status = 'PARSING' → 跳过
  └── afterRow.parse_status = 'NOT_PARSED' → 跳过
```

### 7.2 UPDATE 事件

```
收到 file_info UPDATE 事件
  │
  ├── beforeRow.parse_status ≠ 'PARSE_SUCCESS' AND afterRow.parse_status = 'PARSE_SUCCESS'
  │     → ES UPSERT (index)
  │
  ├── beforeRow.parse_status = 'PARSE_SUCCESS' AND afterRow.parse_status = 'PARSE_SUCCESS'
  │     AND (content / fileName / ossUrl / fileType 任一变化)
  │     → ES UPSERT (update)
  │
  ├── afterRow.parse_status = 'PARSE_FAILED'
  │     → ES DELETE（确保失败文件不出现在搜索结果中）
  │
  ├── upload_status 变更但 parse_status 未 PARSE_SUCCESS
  │     → 跳过
  │
  └── 其他字段变更（file_size / file_md5 / total_chunks 等）
        → 跳过（这些字段不在 ES 中）
```

### 7.3 DELETE 事件

```
收到 file_info DELETE 事件
  │
  └── → ES DELETE (delete by document id = beforeRow.id)
```

### 7.4 Document ID 映射

| MySQL | ES |
|:--|:--|
| `file_info.id` (BIGINT) | `file_index._id` (String) = `String.valueOf(id)` |

与当前 Consumer 写入方式完全一致，无需修改 ES mapping。

### 7.5 ES 字段映射

| MySQL 列 | ES 字段 | 类型 | 说明 |
|:--|:--|:--|:--|
| `id` | `id` | long | document id（也用作 `_id`） |
| `file_name` | `fileName` | text | 全文搜索 |
| `content` | `content` | text | 全文搜索（⚠️ 大字段） |
| `file_type` | `fileType` | keyword | 精确匹配/过滤 |
| `oss_url` | `ossUrl` | keyword | 精确匹配/存储 |

字段来自 `file_info` 各列的 after-image。`status` / `parseStatus` / `uploadStatus` / `fileMd5` / `fileSize` 等字段暂不同步到 ES（当前 ES mapping 和搜索逻辑不依赖这些字段）。

---

## 8. Phase 3 闭环拆分

### 闭环 1：设计文档 ✅（当前闭环）

| 项 | 内容 |
|:--|:--|
| **目标** | 输出 Canal 方案正式设计文档 |
| **修改范围** | 新增 `docs/canal-sync-design.md` |
| **验收标准** | ① 文档覆盖当前双写链路 ② 覆盖一致性风险 ③ 覆盖 Canal 可行性 ④ 覆盖方案对比 ⑤ 覆盖同步规则 ⑥ 覆盖闭环拆分 ⑦ 不改任何代码 |

### 闭环 2：Canal 环境与 binlog 验证

| 项 | 内容 |
|:--|:--|
| **目标** | 部署 Canal Server，确认能收到 `file_info` 表的 binlog 事件 |
| **修改范围** | 新增 `CanalConfig.java` + `CanalClient.java`（仅启动连接并打印 binlog 事件，不做 ES 同步）；新增 `application.yml` canal 配置块；新增 `build.gradle` canal 客户端依赖 |
| **验收标准** | ① `docker run canal-server` 启动成功 ② MySQL `SHOW VARIABLES LIKE 'log_bin'` = ON + `binlog_format` = ROW ③ 手动 INSERT/UPDATE/DELETE `file_info` → Canal Client 控制台打印完整 binlog 事件（before/after image） ④ Canal Client 断线重连测试通过 ⑤ Canal Client 优雅关闭测试通过 |

### 闭环 3：Canal Client 接收 file_info 变更并打印日志

| 项 | 内容 |
|:--|:--|
| **目标** | 完善 Canal Client，解析 `file_info` 变更事件并结构化打印 |
| **修改范围** | 完善 `CanalClient.java`（订阅过滤 + 事件解析）；新增 `FileInfoEventHandler.java`（结构化日志输出）；不改 Consumer |
| **验收标准** | ① INSERT 事件正确解析（含所有字段 after-image） ② UPDATE 事件正确解析（含 before-image + after-image） ③ DELETE 事件正确解析（含 before-image） ④ 日志格式可读：`[Canal] INSERT file_info id=123, parseStatus=PARSE_SUCCESS` ⑤ 非 `file_info` 表的变更被忽略 |

### 闭环 4：Canal → ES 同步服务

| 项 | 内容 |
|:--|:--|
| **目标** | FileInfoEventHandler 调用 EsSyncService，按同步规则写入 ES |
| **修改范围** | 新增 `EsSyncService.java`；完善 `FileInfoEventHandler.java`；不改 Consumer |
| **验收标准** | ① INSERT `file_info`（parse_status=PARSE_SUCCESS）→ ES 自动新增文档 ② UPDATE `file_info`（parse_status: PARSING → PARSE_SUCCESS）→ ES 自动更新文档 ③ UPDATE `file_info`（parse_status → PARSE_FAILED）→ ES 自动删除文档 ④ DELETE `file_info` → ES 自动删除文档 ⑤ parse_status ≠ PARSE_SUCCESS 时不写入 ES ⑥ content 字段正确同步到 ES ⑦ ES 写入失败时写入 `es_sync_log` 表（status=FAILED） |

### 闭环 5：移除 Consumer 直接写 ES

| 项 | 内容 |
|:--|:--|
| **目标** | 从 `FileUploadConsumer` 中删除 ES 写入代码，改为可配置开关 |
| **修改范围** | 修改 `FileUploadConsumer.java`（删除 `elasticsearchClient.index()` 7 行）；新增配置项 `file.es.sync-mode: canal`（可选值：`canal` / `dual-write` 兼容模式） |
| **验收标准** | ① Consumer 处理成功后不再直接写 ES ② Canal 同步到 ES 后可搜索到新文件 ③ Consumer 处理失败后 ES 中不出现该文件 ④ 普通上传 → 搜索全链路回归通过 ⑤ 分片合并 → Consumer → 搜索全链路回归通过 ⑥ `sync-mode: dual-write` 回退模式可用（紧急回滚用） |

### 闭环 6：对账与补偿

| 项 | 内容 |
|:--|:--|
| **目标** | 定时对比 MySQL ↔ ES，自动修复不一致，提供手动修复接口 |
| **修改范围** | 新增 `EsReconciliationService.java` + `EsSyncLog.java` (entity) + `EsSyncLogMapper.java` + `es_sync_log.sql`；新增 `POST /api/admin/es/reconcile` 手动对账 + `POST /api/admin/es/reindex` 全量重建 |
| **验收标准** | ① `@Scheduled` 每天凌晨自动对账 ② MySQL PARSE_SUCCESS 但 ES 缺失 → 自动补写 ③ ES 存在但 MySQL 已删除/PARSE_FAILED → 自动删除 ④ 手动触发对账返回差异列表（数量 + fileId 列表） ⑤ 全量 reindex 接口可用（脱机重建） ⑥ 连续 100 次上传后对账结果为 0 差异 ⑦ `es_sync_log` 表可追踪每次同步操作的状态 |

### 闭环 7：Phase 3 复盘文档

| 项 | 内容 |
|:--|:--|
| **目标** | 沉淀 Canal 部署指南、故障排查手册，更新架构文档 |
| **修改范围** | 新增 `docs/canal-setup-guide.md`；更新 `docs/architecture.md`（数据流图增加 Canal 同步层）；更新 `README.md`（Phase 3 状态 + 数据流描述） |
| **验收标准** | ① 新人按 `canal-setup-guide.md` 可从零部署 Canal 并验证同步正常 ② `architecture.md` 数据流图反映 Canal 同步链路 ③ `README.md` Phase 3 标记完成 ④ 故障场景排查指南覆盖：Canal Server 宕机、ES 不可用、binlog 位点丢失 |

---

## 9. 预计修改文件清单

> **注意**：闭环 1 只新增本文档（`docs/canal-sync-design.md`）。以下清单为闭环 2-7 的完整预计范围，**本次不实现**。

### 预计新增文件（闭环 2-7）

| # | 文件 | 闭环 | 说明 |
|:--|:--|:--|:--|
| 1 | `config/CanalConfig.java` | 闭环 2 | Canal 连接配置（`@ConfigurationProperties("canal")`） |
| 2 | `canal/CanalClient.java` | 闭环 2/3 | Canal 客户端：连接管理 + 订阅 + 事件分发 + 断线重连 |
| 3 | `canal/FileInfoEventHandler.java` | 闭环 3/4 | `file_info` 变更事件处理 + 同步规则过滤 |
| 4 | `canal/EsSyncService.java` | 闭环 4 | ES index/update/delete 封装 + 异常降级 |
| 5 | `canal/EsReconciliationService.java` | 闭环 6 | 定时对账 + 自动修复 |
| 6 | `entity/EsSyncLog.java` | 闭环 6 | 同步日志实体 |
| 7 | `mapper/EsSyncLogMapper.java` | 闭环 6 | 同步日志 Mapper |
| 8 | `controller/AdminController.java` | 闭环 6 | 管理接口：`/api/admin/es/reconcile` + `/api/admin/es/reindex` |
| 9 | `docs/canal-setup-guide.md` | 闭环 7 | Canal 部署与配置指南 |

### 预计修改文件（闭环 2-7）

| # | 文件 | 闭环 | 改动内容 |
|:--|:--|:--|:--|
| 1 | `FileUploadConsumer.java` | 闭环 5 | **删除** `elasticsearchClient.index()` 7 行 |
| 2 | `application.yml` | 闭环 2 | **新增** `canal.*` 配置块 |
| 3 | `build.gradle` | 闭环 2 | **新增** Canal 客户端依赖 |
| 4 | `README.md` | 闭环 7 | **更新** Phase 3 状态 + 数据流描述 |
| 5 | `docs/architecture.md` | 闭环 7 | **更新** 数据流图增加 Canal 同步层 |

### 明确不改文件

| # | 文件 | 原因 |
|:--|:--|:--|
| 1 | `SearchController.java` | ES 查询方式无变化 |
| 2 | `FileInfoMapper.java` | MySQL 写入逻辑不变 |
| 3 | `FileInfoService.java` | 业务事务不变 |
| 4 | `FileDocument.java` | ES 字段结构不变（Phase 3 后期可考虑加 parseStatus） |
| 5 | `FileInfo.java` | 实体不变 |
| 6 | `DeadLetterConsumer.java` | 死信处理不变 |
| 7 | `RabbitMqConfig.java` | MQ 配置不变 |
| 8 | `FileUploadProducer.java` | Producer 不变 |
| 9 | `ChunkUploadService.java` | 分片逻辑不变 |
| 10 | `FileController.java` | 上传接口不变 |

---

## 10. 风险与边界

### 10.1 风险清单

| # | 风险 | 等级 | 触发场景 | 缓解措施 |
|:--|:--|:--|:--|:--|
| 1 | **Canal Server 宕机** | 🔴 P0 | Docker 容器崩溃 / 宿主机重启 | Canal Client 内置断线重连 + 指数退避；对账任务兜底修复遗漏数据 |
| 2 | **Canal Client 位点丢失** | 🟡 P1 | Client 本地位点文件损坏 / 误删 | 对账任务全量对比兜底；`POST /api/admin/es/reindex` 全量重建 |
| 3 | **content 大字段 binlog 性能** | 🟡 P1 | 几百 KB ~ 几 MB 的文本内容频繁通过 Canal 传输 | 每条记录生命周期内最多同步一次（PARSE_SUCCESS 时）；实测后如超出阈值，采用 Canal column filtering 跳过 content 列，改由 Consumer 直接写 ES 的 content 字段 |
| 4 | **存量数据无 binlog 记录** | 🟡 P1 | Phase 0-2 已产生的 PARSE_SUCCESS 数据 | 提供全量 reindex 接口，Phase 3 部署后首次执行 |
| 5 | **Canal 同步延迟** | 🟢 P2 | 高负载下 binlog 消费延迟 > 1s | 最终一致性可接受秒级延迟；对账任务可修复长期遗漏 |
| 6 | **ES mapping 变更** | 🟢 P2 | 新增字段 / 修改分词器 / 新增索引 | 使用索引别名 + Reindex API 零停机切换 |
| 7 | **MySQL REPLICATION 权限** | 🟢 P2 | 云数据库限制 REPLICATION 权限 | Docker 本地开发不受限；云数据库需提前确认策略 |
| 8 | **删除事件与软删除策略** | 🟢 P2 | 当前无文件删除功能，未来可能引入软删除 | 设计同步规则时预留：物理 DELETE → ES DELETE；软删除（status=-1）→ ES DELETE |

### 10.2 边界声明

1. **Canal 不解决 MySQL 写入前的消息丢失问题**。该问题已由 Phase 1（Producer Confirm + MANUAL ACK + DLX/DLQ）解决。
2. **Canal 解决的是 MySQL 已提交之后到 ES 的同步问题**。binlog 只包含已提交的事务，未提交的事务不会出现在 binlog 中。
3. **Canal 不等于强一致**。binlog 消费有延迟（通常 < 1 秒），是最终一致性方案。用户上传成功后的 1-2 秒内可能搜不到——这是最终一致性的固有特性，非缺陷。
4. **Canal Client 挂机期间的数据变更不会丢失**。MySQL binlog 持久化在磁盘，Client 重启后从上次记录的位点继续消费。
5. **es_sync_log 仅记录同步失败事件**。不做重试队列——同步失败由对账任务兜底。
6. **当前不做分布式事务**。Canal 本身是异步方案，不引入 Seata 等分布式事务框架。
7. **后续 RAG 的 text_chunk 表可直接复用 Canal 同步通道**。只需在 Canal Client 中新增 `text_chunk` 表的订阅规则，无需重构同步基础设施。
8. **Canal 部署依赖 Docker**。不提供裸机安装方案。如果 Docker 不可用，退化为方案 B（Outbox 表）。

---

## 11. 验收标准

### 闭环 1 验收标准（当前）

| # | 标准 | 状态 |
|:--|:--|:--:|
| 1 | `docs/canal-sync-design.md` 已新建 | ✅ |
| 2 | 文档覆盖当前双写链路（MySQL 写入位置 + ES 写入位置 + 成功/失败/分片合并路径） | ✅ |
| 3 | 文档覆盖一致性风险（8 个场景分析） | ✅ |
| 4 | 文档覆盖 Canal 可行性（10 项检查） | ✅ |
| 5 | 文档覆盖方案 A/B/C 对比 | ✅ |
| 6 | 文档明确推荐方案 A（Canal 直接同步） | ✅ |
| 7 | 文档覆盖同步规则（INSERT/UPDATE/DELETE + 字段映射） | ✅ |
| 8 | 文档覆盖 Phase 3 闭环拆分（7 个闭环） | ✅ |
| 9 | 文档覆盖风险清单（8 项） | ✅ |
| 10 | 本次没有修改任何代码、配置、SQL | ✅ |
| 11 | 可以进入 Phase 3 闭环 2 | ✅ |

---

> **文档结束** · 下一步：Phase 3 闭环 2（Canal 环境搭建与 binlog 验证）。
