# Phase 2 · 大文件上传工程化 — 状态设计

> 版本: v1.0 | 创建: 2026-07-15 | 所属: Smart File Processor

---

## 目录

1. [Phase 2 总览](#1-phase-2-总览)
2. [为何新增 file_md5](#2-为何新增-file_md5)
3. [为何拆分 upload_status 和 parse_status](#3-为何拆分-upload_status-和-parse_status)
4. [状态流转图](#4-状态流转图)
5. [与原有 status 字段的兼容](#5-与原有-status-字段的兼容)
6. [本地分片上传闭环数据流](#6-本地分片上传闭环数据流)
7. [闭环拆分与实施计划](#7-闭环拆分与实施计划)

---

## 1. Phase 2 总览

Phase 2 目标：**支持大文件（GB 级）上传，实现 MD5 秒传、分片上传、断点续传**。

当前阶段实现策略：先完成"本地分片上传闭环"，即全部分片存本地临时目录，服务端流式合并，合并后复用现有 MQ Consumer 完成 OSS 上传 + 文本解析 + ES 索引。

**Phase 2 拆分 4 个闭环**：

```
闭环 1（本次）: 文件元数据与状态设计   ← 数据层准备
闭环 2:        MD5 秒传接口            ← /api/file/chunk/check
闭环 3:        分片上传 + 断点续传      ← /api/file/chunk/init + /upload
闭环 4:        服务端流式合并 + 删片    ← /api/file/chunk/merge
```

---

## 2. 为何新增 file_md5

### 解决的问题

| 场景 | 没有 MD5 | 有了 MD5 |
|------|----------|----------|
| **秒传** | 同一文件传两次 = 两次完整上传 + 两份 OSS 存储 | 客户端先发 MD5 → 服务端查到已存在 → 直接返回已有链接 |
| **断点续传** | 不知道哪些分片已成功 | 按 `file_md5` 查 `file_chunk` 表 → 返回已上传分片索引 |
| **完整性校验** | 合并后无法验证文件是否损坏 | 合并后流式计算 MD5 与客户端上报值对比 |
| **去重** | 多用户上传同一份合同，重复存储 | 秒传可跳过上传，OBS 层面对同一个 OssKey 只存一份 |

### 计算方式

客户端在发送文件前，用 JavaScript 或其它语言流式计算整个文件的 MD5，然后将 MD5 作为参数提交到服务端。服务端**不代替客户端计算**（大文件服务端计算无意义，也是瓶颈）。

服务端只在**分片合并后**计算一次 MD5 做完整性校验。

### 存储

`file_info.file_md5` — VARCHAR(32)，存储 32 位十六进制字符串（不区分大小写，统一转小写存）。

---

## 3. 为何拆分 upload_status 和 parse_status

### 原 status 的问题

原有 `status` 字段混合了两个维度的状态：

```
status = 0  → "待处理"  → 实际上既表示"文件已上传"又表示"等待解析"
status = 2  → "已完成"  → 既表示"OSS 上传成功"又表示"解析成功"
status = 3  → "失败"    → 不知道是上传失败还是解析失败
```

在大文件分片上传场景下，**上传和解析是两个独立的长流程**：

- **上传**：可能需要几分钟到几十分钟（分片逐一上传 → 合并 → 验证完整性）
- **解析**：合并后由 MQ Consumer 异步完成（OSS 上传 → 文本提取 → ES 索引）

如果不拆分，将无法区分"分片正在上传中"和"文件已就绪等待解析"。

### 拆分后的模型

| 维度 | 字段 | 枚举值 | 生命周期 |
|------|------|--------|----------|
| 上传 | `upload_status` | INIT → UPLOADING → MERGING → UPLOADED / MERGE_FAILED | 从创建记录到本地完整文件就绪 |
| 解析 | `parse_status` | NOT_PARSED → WAITING_PARSE → PARSING → PARSE_SUCCESS / PARSE_FAILED | 从文件就绪到 OSS + 文本 + ES 全部完成 |

**两个维度正交变化**，状态组合示例：

| upload_status | parse_status | 含义 |
|:---|:---|:---|
| UPLOADING | NOT_PARSED | 分片正在上传，还没到解析阶段 |
| UPLOADED | NOT_PARSED | 文件已就绪（简单上传），等待投递 MQ |
| UPLOADED | WAITING_PARSE | 已投递 MQ，等待消费 |
| UPLOADED | PARSE_SUCCESS | 全部完成（等同于旧 status=2） |
| UPLOADED | PARSE_FAILED | 文件上传成功但解析失败（等同于旧 status=3） |
| MERGE_FAILED | NOT_PARSED | 分片合并失败，需人工处理 |

---

## 4. 状态流转图

### 4.1 简单上传（现有兼容模式）

```
FileController.upload()
  │
  ├── FileInfoService.saveFileInfo()
  │     upload_status = UPLOADED
  │     parse_status  = NOT_PARSED
  │     is_chunked    = false
  │     total_chunks  = 1
  │     uploaded_chunks = 1
  │
  ├── 存入本地磁盘
  │
  ├── FileUploadProducer.sendUploadTask(msg)
  │     → parse_status 隐式进入 WAITING_PARSE（消息已投递）
  │
  └── Consumer 消费:
        ├── 成功 → updateStatus(2, content, ossUrl)
        │            → parse_status = PARSE_SUCCESS
        └── 失败 → updateStatus(3, errorMsg, null)
                     → parse_status = PARSE_FAILED
```

### 4.2 分片上传（闭环 2-4 实现）

```
[闭环2] 客户端发 MD5
  │
  ├── POST /api/file/chunk/check  {fileMd5}
  │     ├── EXISTS  → 返回 {exists:true, fileId, ossUrl}  ← 秒传，流程结束
  │     └── !EXISTS → 返回 {exists:false, uploadedChunks:[]}
  │
[闭环3] 客户端初始化上传
  │
  ├── POST /api/file/chunk/init  {fileName, fileSize, fileMd5, totalChunks, chunkSize}
  │     └── INSERT file_info:
  │           upload_status  = INIT
  │           parse_status   = NOT_PARSED
  │           is_chunked     = true
  │           total_chunks   = 100
  │           uploaded_chunks = 0
  │
  ├── [循环] POST /api/file/chunk/upload  {chunk, fileId, chunkIndex}
  │     │  upload_status = UPLOADING
  │     │  每个分片:
  │     │    ├── 存到 uploads/chunks/{fileId}_{chunkIndex}.part
  │     │    ├── INSERT file_chunk (fileId, chunkIndex, status=1)
  │     │    └── uploaded_chunks++
  │     │
  │     └── [断点续传] 如果中断 → /chunk/check 返回已上传的 chunkIndex 列表
  │           客户端只补传缺失分片
  │
[闭环4] 全部分片上传完成 → 合并
  │
  ├── POST /api/file/chunk/merge  {fileId}
  │     upload_status = MERGING
  │     ├── 按 chunkIndex 0→99 依次读取 .part 文件
  │     ├── 流式写入最终文件 uploads/{fileId}_{fileName}
  │     ├── 流式计算合并后 MD5，与客户端上报值对比
  │     ├── 成功:
  │     │     upload_status  = UPLOADED
  │     │     uploaded_chunks = total_chunks (100)
  │     │     merge_time     = NOW()
  │     │     删除 .part 文件
  │     │     发送 MQ 消息 → parse_status = WAITING_PARSE
  │     │                                          ↓
  │     │                              复用现有 Consumer 链路
  │     │                              OSS 上传 → 文本提取 → ES 索引
  │     │                                          ↓
  │     │                              PARSE_SUCCESS / PARSE_FAILED
  │     │
  │     └── 失败:
  │           upload_status = MERGE_FAILED
  │           保留 .part 文件（允许重新合并）
  └
```

---

## 5. 与原有 status 字段的兼容

| 旧 status | 等价新状态组合 | 说明 |
|:----------|:---------------|:-----|
| `0` (待处理) | `upload_status=UPLOADED, parse_status=NOT_PARSED` | 简单上传文件已就绪 |
| `1` (处理中) | `upload_status=UPLOADED, parse_status=PARSING` | 仅 Phase 1 补全后启用 |
| `2` (已完成) | `upload_status=UPLOADED, parse_status=PARSE_SUCCESS` | 全部完成 |
| `3` (失败) | `upload_status=UPLOADED, parse_status=PARSE_FAILED` | 解析失败 |

> **原则**：`status` 字段**不删除**、**不废弃**，所有现有代码继续使用。新代码优先读写 `upload_status` / `parse_status`。当两者不一致时，以新字段为准。

---

## 6. 本地分片上传闭环数据流

```
┌──────────────────────────────────────────────────────────────────┐
│                         客户端                                    │
│  1. 计算文件 MD5（流式）                                          │
│  2. POST /api/file/chunk/check → 判断秒传 / 断点续传              │
│  3. POST /api/file/chunk/init  → 创建上传任务                     │
│  4. 循环 POST /api/file/chunk/upload → 逐片上传（支持并发）       │
│  5. POST /api/file/chunk/merge → 触发合并                         │
└──────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────┐
│                        Spring Boot 服务端                         │
│                                                                   │
│  ChunkUploadController                                            │
│  ├── /chunk/check  → ChunkUploadService.md5Check()               │
│  ├── /chunk/init   → ChunkUploadService.initUpload()             │
│  ├── /chunk/upload → ChunkUploadService.uploadChunk()            │
│  └── /chunk/merge  → ChunkUploadService.mergeChunks()            │
│                                                                   │
│  ChunkUploadService（闭环 2-4 实现）                              │
│  ├── 查 file_info (file_md5 → upload_status → uploaded_chunks)   │
│  ├── 写 uploads/chunks/{fileId}_{chunkIndex}.part                │
│  ├── 读 .part 文件 → 流式拼接 → uploads/{fileId}_{fileName}      │
│  └── 发 MQ 消息 → 复用现有 Consumer 链路                          │
│                                                                   │
│  复用现有链路（不改代码）:                                        │
│    FileUploadConsumer.handleFileUpload(msg)                       │
│    ├── OSS 上传（OssService.uploadFile）                          │
│    ├── 文本提取（FileContentExtractor）                           │
│    ├── 更新 MySQL（status=2, parse_status=PARSE_SUCCESS）        │
│    ├── 索引 ES                                                    │
│    └── 删除临时文件                                                │
└──────────────────────────────────────────────────────────────────┘
```

---

## 7. 闭环拆分与实施计划

| 闭环 | 内容 | 新增/修改 | 状态 |
|:-----|:-----|:----------|:---:|
| **闭环 1** | 字段扩展 + 状态枚举 + SQL 脚本 | FileInfo, FileInfoMapper, FileInfoService + 2 枚举 + 2 SQL | ✅ |
| **闭环 2** | MD5 秒传接口 | ChunkUploadController, ChunkUploadService, Md5CheckRequest/Response, FileInfoMapper.findByMd5AndSizeAndStatus | ✅ |
| **闭环 3** | 分片上传 + 断点续传 | file_chunk 表, ChunkUploadController.init/upload, FileChunkMapper, ChunkInit/Upload DTOs | ✅ |
| **闭环 4** | 服务端流式合并 + 删片 | ChunkUploadController.merge, 流式合并, MD5 完整性校验, MQ 触发 | ✅ |

---

## 8. 闭环 2 实现详情：MD5 秒传检查

### 8.1 接口

```
POST /api/file/chunk/check
Content-Type: application/json

Request:
{
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "fileSize": 1048576,
  "originalFilename": "年度报告.pdf"
}
```

### 8.2 查询逻辑

```
1. 参数校验: fileMd5 非空, fileSize > 0
2. 统一转小写后查询:
   SELECT * FROM file_info
   WHERE file_md5 = ? AND file_size = ? AND upload_status = 'UPLOADED'
   LIMIT 1
3. 命中 → instantUpload=true, 返回已有的 fileId / fileName / ossUrl 等
4. 未命中 → instantUpload=false
```

### 8.3 为什么用 fileMd5 + fileSize

| 只用 fileMd5 | fileMd5 + fileSize |
|:---|:---|
| 理论上有 MD5 碰撞风险（不同文件相同 MD5） | 双重校验，碰撞概率可忽略 |
| 无法区分同名文件的不同版本 | 文件大小不同则视为不同文件 |
| 不安全 | 更安全 |

### 8.4 为什么只要求 upload_status='UPLOADED'

秒传的目的是**跳过上传**，不是跳过解析。文件只要已上传完成（upload_status=UPLOADED），就可以秒传——无论它是否已完成解析（parse_status 任意值）。如果要求 parse_status=PARSE_SUCCESS，则：
- 上传成功但 Consumer 还没来得及处理的文件无法秒传
- 解析失败（但文件本身完整）的文件无法秒传
- 这两类文件本应可以秒传，不应被排除

### 8.5 前端使用流程

```
客户端计算文件 MD5（流式，不阻塞 UI）
  │
  ├── POST /api/file/chunk/check { fileMd5, fileSize, originalFilename }
  │
  ├── instantUpload=true
  │     → 直接展示"上传成功"，复用已有 fileId + ossUrl
  │     → 用户无需等待上传，秒级完成
  │
  └── instantUpload=false
        → 继续正常上传流程（小文件走 /api/file/upload，大文件走分片上传）
```

### 8.6 响应示例

**命中（秒传）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "instantUpload": true,
    "fileId": 42,
    "fileName": "年度报告.pdf",
    "fileSize": 1048576,
    "ossUrl": "https://file-processor-yang.oss-cn-hangzhou.aliyuncs.com/files/42_年度报告.pdf",
    "uploadStatus": "UPLOADED",
    "parseStatus": "PARSE_SUCCESS",
    "message": "文件已存在，无需重复上传"
  }
}
```

**未命中：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "instantUpload": false,
    "fileId": null,
    "fileName": null,
    "fileSize": null,
    "ossUrl": null,
    "uploadStatus": null,
    "parseStatus": null,
    "message": "文件不存在，请继续上传"
  }
}
```

**参数错误：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "instantUpload": false,
    "message": "参数错误：fileMd5 不能为空"
  }
}
```

---

## 9. 闭环 3 实现详情：分片上传初始化与单分片上传

### 9.1 file_chunk 表设计

| 列 | 类型 | 说明 |
|:---|:---|:---|
| id | BIGINT PK | 主键 |
| file_id | BIGINT NULL | 关联 file_info.id（init 前可为空） |
| file_md5 | VARCHAR(32) NOT NULL | 完整文件 MD5（分片查询的主键维度） |
| chunk_index | INT NOT NULL | 分片索引，**从 0 开始** |
| chunk_size | BIGINT | 当前分片实际字节数 |
| chunk_path | VARCHAR(500) NOT NULL | 分片本地存储路径 |
| upload_status | VARCHAR(20) NOT NULL | UPLOADED / FAILED |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

唯一约束 `uk_file_md5_chunk_index(file_md5, chunk_index)` 保证同一文件的同一分片只保留一条记录。

### 9.2 分片存储路径

文件存储路径为：`{uploadDir}/chunks/{fileMd5}/{chunkIndex}.part`

每个文件的全部 `chunkIndex.part` 小文件放在同一个以 `fileMd5` 命名的目录下，合并时按 `chunkIndex` 排序拼接。

### 9.3 uploaded_chunks 与 file_chunk 的关系

| 字段 | 用途 | 权威性 |
|:---|:---|:---|
| `file_chunk` 表 | 记录每个分片的状态，是**唯一权威数据源** | ✅ 权威 |
| `file_info.uploaded_chunks` | 快速展示进度（前端 "37/100"） | ⚠️ 仅用于展示 |

每次分片上传后，`file_info.uploaded_chunks` 由 `COUNT(*) FROM file_chunk WHERE ... status='UPLOADED'` 覆写，**不是简单的 +1**。这保证了即使重复上传同一分片，计数不会错误累加。

### 9.4 接口：POST /api/file/chunk/init

```
Request (JSON):
{
  "fileMd5": "abc123...",
  "fileSize": 524288000,
  "originalFilename": "大型设计文档.pdf",
  "totalChunks": 100,
  "chunkSize": 5242880
}

Response:
{
  "code": 200,
  "data": {
    "fileId": 456,
    "fileMd5": "abc123...",
    "uploadStatus": "INIT",
    "uploadedChunks": 0,
    "totalChunks": 100,
    "message": "分片上传任务已创建，共 100 片，每片 5242880 字节"
  }
}
```

初始化逻辑：
1. 校验 fileMd5 / fileSize / totalChunks / chunkSize / originalFilename 合法性
2. 如果 fileMd5 + fileSize 已存在 UPLOADED 记录 → 返回"已可秒传"
3. 如果 fileMd5 已存在进行中任务 → 复用已有 fileId，返回当前进度
4. 否则新建 file_info 记录（upload_status=INIT, is_chunked=true）

### 9.5 接口：POST /api/file/chunk/upload

```
Request (multipart/form-data):
  chunk:        <binary>           ← 分片文件内容
  fileMd5:      "abc123..."
  chunkIndex:   3                  ← 从 0 开始
  totalChunks:  100
  chunkSize:    5242880

Response:
{
  "code": 200,
  "data": {
    "fileMd5": "abc123...",
    "chunkIndex": 3,
    "uploadedChunks": 37,
    "totalChunks": 100,
    "uploadStatus": "UPLOADING",
    "message": "分片 3 上传成功，进度 37/100"
  }
}
```

上传逻辑：
1. 校验参数合法性
2. 创建目录 `uploads/chunks/{fileMd5}/`
3. `InputStream → OutputStream` 流式写入 `.part` 文件（不使用 `getBytes()`）
4. `INSERT ... ON DUPLICATE KEY UPDATE` 写入 file_chunk
5. 从 file_chunk 表 `COUNT(*)` 统计已上传分片数
6. 更新 `file_info.uploaded_chunks`（覆写，不累加）
7. 如果 `uploaded == total` → upload_status 自动切换为 `READY_TO_MERGE`

### 9.6 状态流转更新

分片上传场景下，`UploadStatusEnum` 新增了 `READY_TO_MERGE`：

```
INIT → UPLOADING → READY_TO_MERGE → MERGING → UPLOADED
                                      ↘ MERGE_FAILED
```

`READY_TO_MERGE` 由 `updateChunkProgress()` 自动设置（当 `uploadedChunks >= totalChunks` 时）。

### 9.7 本阶段保留项（闭环 4 已实现）

- ✅ 分片合并（`/api/file/chunk/merge`） → 见第 10 节
- ✅ 合并后触发 MQ 消息 → 见第 10 节
- ✅ 分片文件清理 → 见第 10 节
- ❌ OSS Multipart Upload（Phase 2 不做）
- ❌ `file_chunk` 表与 OSS ETag 的映射（Phase 2 不做）

---

## 10. 闭环 4 实现详情：分片合并 + MD5 校验

### 10.1 接口：POST /api/file/chunk/merge

```
Request (JSON):
{
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e"
}

Response (成功):
{
  "code": 200,
  "data": {
    "success": true,
    "fileId": 456,
    "fileName": "大型设计文档.pdf",
    "fileSize": 524288000,
    "uploadStatus": "UPLOADED",
    "parseStatus": "WAITING_PARSE",
    "storagePath": "uploads/456_大型设计文档.pdf",
    "message": "分片合并成功，已进入异步解析流程"
  }
}

Response (失败):
{
  "code": 200,
  "data": {
    "success": false,
    "fileId": 456,
    "uploadStatus": "READY_TO_MERGE",
    "message": "分片数量不完整：期望 100 片，实际已上传 99 片"
  }
}
```

### 10.2 合并全流程

```
客户端调用 POST /api/file/chunk/merge { fileMd5 }
  │
  ├── 1. 查询 file_info WHERE file_md5 = ?
  │     └── 不存在 → 返回 fail("文件不存在")
  │
  ├── 2. 校验 upload_status == READY_TO_MERGE
  │     └── 不是 → 返回 fail("当前状态不允许合并")
  │
  ├── 3. 查询 file_chunk WHERE file_md5 = ? ORDER BY chunk_index
  │     ├── chunks.size() != total_chunks → fail("分片数量不完整")
  │     ├── chunkIndex 不连续 → fail("缺少 chunkIndex=N")
  │     ├── 存在 chunk.uploadStatus != UPLOADED → fail("分片状态异常")
  │     └── .part 文件不存在 → fail("分片文件不存在")
  │
  ├── 4. UPDATE upload_status = 'MERGING'
  │
  ├── 5. 流式合并：按 chunkIndex 0→N-1 依次读取 .part → OutputStream 写入
  │     └── IO 异常 → upload_status = 'MERGE_FAILED'，删除不完整文件
  │
  ├── 6. 流式计算完整文件 MD5
  │     └── 与 file_info.file_md5 对比
  │
  ├── 7. MD5 不匹配：
  │     ├── 删除合并后的完整文件
  │     ├── upload_status = 'MERGE_FAILED'
  │     ├── 保留 .part 文件（可重新合并）
  │     └── 返回 fail("MD5 校验失败")
  │
  ├── 8. MD5 匹配（@Transactional）：
  │     ├── UPDATE upload_status = 'UPLOADED'
  │     ├── UPDATE uploaded_chunks = total_chunks
  │     ├── UPDATE storage_path = 合并后文件路径
  │     ├── UPDATE merge_time = NOW()
  │     └── UPDATE parse_status = 'WAITING_PARSE'
  │
  ├── 9. 发送 FileUploadMessage { fileId, filePath, fileName }
  │     └── 复用现有 FileUploadConsumer:
  │         OSS 上传 → 文本提取 → status=2, parse_status=PARSE_SUCCESS → ES 索引
  │     ├── MQ 发送成功 → 继续执行步骤 10
  │     └── MQ 发送失败 → 返回 fail(…)，**跳过步骤 10**，.part 和合并文件均保留
  │
  └── 10. 清理 .part 分片文件和空目录（仅 MQ 发送成功后执行）
        └── 返回 success("分片合并成功，已进入异步解析流程")
```

### 10.3 MD5 校验

- 服务端使用 `java.security.MessageDigest.getInstance("MD5")` 流式计算合并后文件的 MD5
- 与 `file_info.file_md5`（客户端上报值）做**严格区分大小写**的对比
- MD5 值统一为**小写**（客户端和服务端都转小写），避免大小写不一致导致误判
- 合并文件使用 `BufferedInputStream(8KB)` 读取，不会将整个文件加载到内存

### 10.4 事务边界

| 步骤 | 事务 | 说明 |
|:---|:---|:---|
| 状态校验 + 分片完整性检查 | 无事务 | 只读操作 |
| 更新 MERGING | 自动提交 | 单条 UPDATE，失败即停 |
| 文件合并 + MD5 计算 | 无事务 | 纯文件 I/O，不可回滚 |
| MD5 失败处理 | 手动清理 | 删除合并文件 + UPDATE MERGE_FAILED |
| **MD5 成功后 DB 更新** | `@Transactional` | updateMergeInfo + updateParseStatus 原子写入 |
| MQ 发送 | 事务外 | 失败不影响已提交的 DB 状态 |
| .part 清理 | 无事务 | 删除文件，失败不影响结果 |

### 10.5 .part 文件清理策略

合并成功 + MQ 发送后，立即删除 `.part` 分片文件并尝试删除空的分片目录。
如果 MQ 发送失败（`sendUploadTask` 抛出异常），方法在清理逻辑之前提前 return，
分片文件**保留**，完整合并文件也保留在 `storagePath`，管理员可排查 MQ 异常后手动补发消息。

**不会删除 .part 的场景**：
- 分片校验未通过（未进入合并阶段）
- MD5 校验失败（`.part` 保留用于重新合并或排查）
- 合并 IO 异常（`.part` 保留）

### 10.6 错误场景完整列表

| 错误场景 | upload_status 变化 | .part 清理 | MQ 发送 |
|:---|:---|:---|:---|
| 文件不存在 | 不变 | - | - |
| 状态不是 READY_TO_MERGE | 不变 | - | - |
| 分片数量不完整 | 不变 | - | - |
| chunkIndex 不连续 | 不变 | - | - |
| 分片状态非 UPLOADED | 不变 | - | - |
| 分片 .part 文件缺失 | 不变 | - | - |
| 合并 IO 异常 | → MERGE_FAILED | - | - |
| MD5 校验失败 | → MERGE_FAILED | **保留** | - |
| **MD5 校验成功** | → **UPLOADED** | **清理** | **发送** |
| MQ 发送失败 | UPLOADED（已完成） | **保留** | 失败，需手动补发 |

### 10.7 与原有 Consumer 的对接

合并成功后发送的 `FileUploadMessage` 格式与 `FileController.upload()` 完全一致：

```java
FileUploadMessage message = new FileUploadMessage();
message.setFileId(fileInfo.getId());       // file_info 主键
message.setFilePath(mergedPath);           // 合并后完整文件路径，如 uploads/456_设计文档.pdf
message.setFileName(fileInfo.getFileName()); // 原始文件名
```

`FileUploadConsumer` 收到消息后执行：
1. 上传文件到 OSS → `files/{fileId}_{fileName}`
2. 提取文本内容（PDF/Word/TXT）
3. 更新 DB：`status=2, content=..., ossUrl=..., parse_status=PARSE_SUCCESS`
4. 索引到 Elasticsearch
5. 删除临时文件（即合并后的完整文件）
