# Phase 2 · 分片上传测试指南

> 版本: v1.0 | 创建: 2026-07-15 | 所属: Smart File Processor

---

本文档提供闭环 2-3 已实现接口的 curl 测试示例。

## 前置条件

- 项目已启动：`./gradlew bootRun`
- MySQL 已执行闭环 1 和闭环 3 的 SQL 脚本
- 测试文件：准备一个大于 5MB 的文件（如 `test-data.pdf`），或自己生成

```bash
# 生成 15MB 测试文件（3 个分片，每片 5MB）
dd if=/dev/urandom of=test-data.bin bs=1048576 count=15

# 计算 MD5（Linux/Mac）
md5sum test-data.bin | awk '{print $1}'

# 计算 MD5（Windows PowerShell）
Get-FileHash -Algorithm MD5 test-data.bin | Select-Object -ExpandProperty Hash

# 或者使用 certutil
certutil -hashfile test-data.bin MD5
```

---

## 1. 秒传检查（闭环 2）

### 1.1 未上传过的文件

```bash
curl -X POST http://localhost:8080/api/file/chunk/check \
  -H "Content-Type: application/json" \
  -d '{
    "fileMd5": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
    "fileSize": 15728640,
    "originalFilename": "test-data.bin"
  }'
```

预期响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "instantUpload": false,
    "fileId": null,
    "message": "文件不存在，请继续上传"
  }
}
```

### 1.2 参数错误

```bash
# fileMd5 为空
curl -X POST http://localhost:8080/api/file/chunk/check \
  -H "Content-Type: application/json" \
  -d '{"fileMd5": "", "fileSize": 15728640}'

# 预期: message = "参数错误：fileMd5 不能为空"

# fileSize 为 0
curl -X POST http://localhost:8080/api/file/chunk/check \
  -H "Content-Type: application/json" \
  -d '{"fileMd5": "abc123", "fileSize": 0}'

# 预期: message = "参数错误：fileSize 必须大于 0"
```

---

## 2. 初始化分片上传（闭环 3）

### 2.1 基本初始化

```bash
MD5="a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
curl -X POST http://localhost:8080/api/file/chunk/init \
  -H "Content-Type: application/json" \
  -d "{
    \"fileMd5\": \"$MD5\",
    \"fileSize\": 15728640,
    \"originalFilename\": \"test-data.bin\",
    \"totalChunks\": 3,
    \"chunkSize\": 5242880
  }"
```

预期响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "fileId": 1,
    "fileMd5": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
    "uploadStatus": "INIT",
    "uploadedChunks": 0,
    "totalChunks": 3,
    "message": "分片上传任务已创建，共 3 片，每片 5242880 字节"
  }
}
```

### 2.2 重复初始化（复用已有任务）

再次执行上一条 curl，预期返回相同的 fileId，message 提示"复用已有上传任务"。

### 2.3 参数错误

```bash
# totalChunks <= 0
curl -X POST http://localhost:8080/api/file/chunk/init \
  -H "Content-Type: application/json" \
  -d '{"fileMd5":"abc","fileSize":100,"originalFilename":"t.bin","totalChunks":0,"chunkSize":100}'
```

---

## 3. 上传分片（闭环 3）

### 3.1 上传第 0 片

```bash
# 准备分片 0（文件的前 5MB）
dd if=test-data.bin of=chunk0.part bs=5242880 count=1 skip=0

MD5="a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
curl -X POST http://localhost:8080/api/file/chunk/upload \
  -F "chunk=@chunk0.part" \
  -F "fileMd5=$MD5" \
  -F "chunkIndex=0" \
  -F "totalChunks=3" \
  -F "chunkSize=5242880"
```

预期响应：
```json
{
  "code": 200,
  "data": {
    "fileMd5": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
    "chunkIndex": 0,
    "uploadedChunks": 1,
    "totalChunks": 3,
    "uploadStatus": "UPLOADING",
    "message": "分片 0 上传成功，进度 1/3"
  }
}
```

### 3.2 上传第 1 片

```bash
dd if=test-data.bin of=chunk1.part bs=5242880 count=1 skip=1
curl -X POST http://localhost:8080/api/file/chunk/upload \
  -F "chunk=@chunk1.part" \
  -F "fileMd5=$MD5" \
  -F "chunkIndex=1" \
  -F "totalChunks=3" \
  -F "chunkSize=5242880"
```

### 3.3 上传第 2 片（最后一片）

```bash
dd if=test-data.bin of=chunk2.part bs=5242880 count=1 skip=2
curl -X POST http://localhost:8080/api/file/chunk/upload \
  -F "chunk=@chunk2.part" \
  -F "fileMd5=$MD5" \
  -F "chunkIndex=2" \
  -F "totalChunks=3" \
  -F "chunkSize=5242880"
```

预期 `uploadStatus` 变为 `READY_TO_MERGE`，message 为 "全部 3 片已上传，等待合并"。

### 3.4 重复上传同一分片（验证幂等性）

重新上传 chunk0：
```bash
curl -X POST http://localhost:8080/api/file/chunk/upload \
  -F "chunk=@chunk0.part" \
  -F "fileMd5=$MD5" \
  -F "chunkIndex=0" \
  -F "totalChunks=3" \
  -F "chunkSize=5242880"
```

预期：
- HTTP 200（不报 500）
- `uploadedChunks` 仍为 3（不是 4，不累加）
- `uploadStatus` 仍为 `READY_TO_MERGE`

### 3.5 参数错误

```bash
# chunkIndex 超出范围
curl -X POST http://localhost:8080/api/file/chunk/upload \
  -F "chunk=@chunk0.part" \
  -F "fileMd5=$MD5" \
  -F "chunkIndex=999" \
  -F "totalChunks=3" \
  -F "chunkSize=5242880"
# 预期: message = "参数错误：chunkIndex=999 超出范围 [0, 2]"

# 分片为空
curl -X POST http://localhost:8080/api/file/chunk/upload \
  -F "chunk=@/dev/null" \
  -F "fileMd5=$MD5" \
  -F "chunkIndex=0" \
  -F "totalChunks=3" \
  -F "chunkSize=5242880"
# 预期: message = "参数错误：分片文件不能为空"
```

---

## 4. 分片合并（闭环 4）

> **前置条件**：已按第 2-3 节完成 init + 全部分片上传，upload_status 为 READY_TO_MERGE。

### 4.1 正常合并

```bash
MD5="a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
curl -X POST http://localhost:8080/api/file/chunk/merge \
  -H "Content-Type: application/json" \
  -d "{\"fileMd5\": \"$MD5\"}"
```

预期响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "fileId": 1,
    "fileName": "test-data.bin",
    "fileSize": 15728640,
    "uploadStatus": "UPLOADED",
    "parseStatus": "WAITING_PARSE",
    "storagePath": "uploads/1_test-data.bin",
    "message": "分片合并成功，已进入异步解析流程"
  }
}
```

### 4.2 合并后验证

```bash
# 1. 检查合并后文件存在
ls -la uploads/1_test-data.bin
# 预期: 文件大小 = 15728640（与原始文件一致）

# 2. 对比 MD5
# Linux/Mac:
md5sum uploads/1_test-data.bin | awk '{print $1}'
# 预期: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6

# Windows PowerShell:
Get-FileHash -Algorithm MD5 uploads/1_test-data.bin | Select-Object -ExpandProperty Hash

# 3. 检查分片文件已被清理
ls uploads/chunks/a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6/
# 预期: No such file or directory（分片目录已删除）
```

### 4.3 重复合并（幂等性校验）

再次调用 merge：
```bash
curl -X POST http://localhost:8080/api/file/chunk/merge \
  -H "Content-Type: application/json" \
  -d "{\"fileMd5\": \"$MD5\"}"
```

预期响应（状态已不是 READY_TO_MERGE）：
```json
{
  "code": 200,
  "data": {
    "success": false,
    "fileId": 1,
    "uploadStatus": "UPLOADED",
    "message": "当前状态不允许合并：uploadStatus=UPLOADED，期望 READY_TO_MERGE"
  }
}
```

### 4.4 各种失败场景

```bash
# 不存在的 fileMd5
curl -X POST http://localhost:8080/api/file/chunk/merge \
  -H "Content-Type: application/json" \
  -d '{"fileMd5": "00000000000000000000000000000000"}'
# 预期: message = "文件不存在：fileMd5=..."

# 分片未上传完就合并（chunks 不够）
# 预期: message = "分片数量不完整：期望 3 片，实际已上传 X 片"

# 参数为空
curl -X POST http://localhost:8080/api/file/chunk/merge \
  -H "Content-Type: application/json" \
  -d '{"fileMd5": ""}'
# 预期: message = "参数错误：fileMd5 不能为空"
```

### 4.5 数据库验证合并结果

```sql
-- 查看合并后的文件记录
SELECT id, file_name, file_md5, upload_status, parse_status,
       total_chunks, uploaded_chunks, storage_path, merge_time
FROM file_info WHERE file_md5 = 'a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6';

-- 预期:
-- upload_status   = UPLOADED
-- parse_status    = WAITING_PARSE (MQ 发送后) 或 PARSE_SUCCESS (Consumer 处理后)
-- uploaded_chunks = total_chunks
-- storage_path    = uploads/{id}_{fileName}
-- merge_time      = NOT NULL (有具体时间)
```

---

## 5. 数据库验证

```sql
-- 查看 file_info 中的分片任务
SELECT id, file_name, file_md5, upload_status, total_chunks, uploaded_chunks, is_chunked
FROM file_info WHERE is_chunked = 1;

-- 查看 file_chunk 中的分片记录
SELECT * FROM file_chunk WHERE file_md5 = 'a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6' ORDER BY chunk_index;

-- 验证重复上传不会导致计数错误
SELECT COUNT(*) FROM file_chunk
WHERE file_md5 = 'a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6' AND upload_status = 'UPLOADED';
-- 预期: 3

-- 验证 uploaded_chunks 是否与 file_chunk 表一致
SELECT
  fi.uploaded_chunks AS file_info_count,
  (SELECT COUNT(*) FROM file_chunk WHERE file_md5 = fi.file_md5 AND upload_status = 'UPLOADED') AS file_chunk_count
FROM file_info fi WHERE fi.is_chunked = 1;
```

---

## 6. 文件系统验证

```bash
# 查看分片文件
ls -la uploads/chunks/a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6/
# 预期: 0.part, 1.part, 2.part 三个文件

# 验证合并后的完整性（手动合并）
cat uploads/chunks/a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6/0.part \
    uploads/chunks/a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6/1.part \
    uploads/chunks/a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6/2.part > merged.bin

diff merged.bin test-data.bin  # 应无差异
```

---

## 7. 完整流程（一键脚本）

```bash
#!/bin/bash
# 假设 test-data.bin 已准备好，MD5=abc123...
MD5="a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
BASE="http://localhost:8080/api/file/chunk"

echo "=== Step 1: Check ==="
curl -s -X POST "$BASE/check" -H "Content-Type: application/json" \
  -d "{\"fileMd5\":\"$MD5\",\"fileSize\":15728640}" | python3 -m json.tool

echo -e "\n=== Step 2: Init ==="
curl -s -X POST "$BASE/init" -H "Content-Type: application/json" \
  -d "{\"fileMd5\":\"$MD5\",\"fileSize\":15728640,\"originalFilename\":\"test.bin\",\"totalChunks\":3,\"chunkSize\":5242880}" | python3 -m json.tool

echo -e "\n=== Step 3: Upload chunks ==="
for i in 0 1 2; do
  echo "Uploading chunk $i..."
  dd if=test-data.bin of=chunk${i}.part bs=5242880 count=1 skip=$i 2>/dev/null
  curl -s -X POST "$BASE/upload" \
    -F "chunk=@chunk${i}.part" \
    -F "fileMd5=$MD5" \
    -F "chunkIndex=$i" \
    -F "totalChunks=3" \
    -F "chunkSize=5242880" | python3 -m json.tool
done

echo -e "\n=== Step 4: Merge ==="
curl -s -X POST "$BASE/merge" -H "Content-Type: application/json" \
  -d "{\"fileMd5\":\"$MD5\"}" | python3 -m json.tool

echo -e "\n=== Step 5: Re-check (should be instant) ==="
curl -s -X POST "$BASE/check" -H "Content-Type: application/json" \
  -d "{\"fileMd5\":\"$MD5\",\"fileSize\":15728640}" | python3 -m json.tool

# Cleanup
rm -f chunk*.part merged.bin
```
