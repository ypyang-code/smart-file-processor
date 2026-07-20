# 性能测试指南

> Smart File Processor · 压测执行说明
> 最后更新：2026-07-16

本文档说明如何在本机对 Smart File Processor 执行可复现的性能测试。所有测试依赖标准工具（`curl`、shell 脚本），不需要 JMeter 或其他压测平台。

---

## 环境要求

- Bash shell（Linux / macOS / Windows Git Bash / WSL2）
- `curl` ≥ 7.x
- `bc`（用于耗时计算，可选）
- JDK 17 + Docker（运行中间件）
- 应用已启动（`./gradlew bootRun`）

---

## 前置准备

```bash
# 1. 启动中间件
docker compose up -d

# 2. 等待所有服务 healthy
docker compose ps

# 3. 初始化 ES 索引
curl -X PUT http://localhost:9200/file_index -H "Content-Type: application/json" -d '{
  "mappings": {
    "properties": {
      "id":       { "type": "long" },
      "fileName": { "type": "text" },
      "content":  { "type": "text" },
      "fileType": { "type": "keyword" },
      "ossUrl":   { "type": "keyword" }
    }
  }
}'

# 4. 启动应用
./gradlew bootRun
```

---

## 测试 1：生成测试文件

```bash
# 生成不同大小的文本文件
# 1KB
dd if=/dev/urandom of=perf_1kb.txt bs=1024 count=1 2>/dev/null

# 100KB
dd if=/dev/urandom of=perf_100kb.txt bs=1024 count=100 2>/dev/null

# 1MB
dd if=/dev/urandom of=perf_1mb.txt bs=1048576 count=1 2>/dev/null

# 5MB
dd if=/dev/urandom of=perf_5mb.txt bs=1048576 count=5 2>/dev/null

# 10MB
dd if=/dev/urandom of=perf_10mb.txt bs=1048576 count=10 2>/dev/null

# 15MB（用于测试超限截断）
dd if=/dev/urandom of=perf_15mb.txt bs=1048576 count=15 2>/dev/null
```

**Windows Git Bash 注意事项**：`dd` 命令在 Git Bash 中可用但行为可能略有差异。如果 `dd` 不可用，可使用以下替代方案：

```bash
# Windows Git Bash 替代：用 PowerShell 生成测试文件
powershell -Command "[System.IO.File]::WriteAllBytes('perf_1kb.txt', (New-Object byte[1024]))"
```

---

## 测试 2：上传接口基准

```bash
# 单文件上传计时
curl -s -o /dev/null -w "HTTP %{http_code} 耗时 %{time_total}s\n" \
  -F "file=@perf_100kb.txt" http://localhost:8080/api/file/upload
```

### 批量上传脚本

保存为 `test-upload.sh` 并执行 `bash test-upload.sh`：

```bash
#!/bin/bash
# test-upload.sh — 文件上传压测脚本
# 用法: bash test-upload.sh [请求数] [文件路径]
set -e

REQUESTS=${1:-100}
TESTFILE=${2:-perf_100kb.txt}
BASE_URL="http://localhost:8080/api/file/upload"

echo "=== Smart File Processor 上传压测 ==="
echo "请求数: $REQUESTS"
echo "文件: $TESTFILE ($(stat -c%s "$TESTFILE" 2>/dev/null || echo 'unknown') bytes)"
echo "======================================"

SUCCESS=0
FAIL=0
TOTAL_TIME=0

for i in $(seq 1 "$REQUESTS"); do
    RESULT=$(curl -s -o /dev/null -w "%{http_code} %{time_total}" \
        -F "file=@${TESTFILE}" "${BASE_URL}")
    HTTP_CODE=$(echo "$RESULT" | cut -d' ' -f1)
    ELAPSED=$(echo "$RESULT" | cut -d' ' -f2)

    if [ "$HTTP_CODE" = "200" ]; then
        SUCCESS=$((SUCCESS + 1))
    else
        FAIL=$((FAIL + 1))
        echo "  [FAIL] 请求 #$i: HTTP $HTTP_CODE"
    fi

    # 进度指示
    if [ $((i % 10)) -eq 0 ]; then
        echo "  进度: $i/$REQUESTS (成功: $SUCCESS, 失败: $FAIL)"
    fi
done

echo "======================================"
echo "完成: 成功 $SUCCESS / 失败 $FAIL / 总计 $REQUESTS"
echo "结果请填入 docs/performance-report.md"
```

---

## 测试 3：搜索接口基准

保存为 `test-search.sh` 并执行 `bash test-search.sh`：

```bash
#!/bin/bash
# test-search.sh — 全文搜索压测脚本
# 用法: bash test-search.sh [请求数] [关键词]
# 前置: ES 中已有索引数据
set -e

REQUESTS=${1:-100}
KEYWORD=${2:-test}
BASE_URL="http://localhost:8080/api/search"

echo "=== Smart File Processor 搜索压测 ==="
echo "请求数: $REQUESTS"
echo "关键词: '$KEYWORD'"
echo "======================================"

SUCCESS=0
FAIL=0

for i in $(seq 1 "$REQUESTS"); do
    RESULT=$(curl -s -o /dev/null -w "%{http_code} %{time_total}" \
        "${BASE_URL}?keyword=${KEYWORD}")
    HTTP_CODE=$(echo "$RESULT" | cut -d' ' -f1)

    if [ "$HTTP_CODE" = "200" ]; then
        SUCCESS=$((SUCCESS + 1))
    else
        FAIL=$((FAIL + 1))
    fi

    if [ $((i % 10)) -eq 0 ]; then
        echo "  进度: $i/$REQUESTS"
    fi
done

echo "======================================"
echo "完成: 成功 $SUCCESS / 失败 $FAIL / 总计 $REQUESTS"
echo "结果请填入 docs/performance-report.md"
```

---

## 测试 4：分页接口

```bash
# 首页 20 条
curl -s -o /dev/null -w "HTTP %{http_code} 耗时 %{time_total}s\n" \
  "http://localhost:8080/api/file/list?page=1&size=20"

# 深层翻页（需要足够数据量）
curl -s -o /dev/null -w "HTTP %{http_code} 耗时 %{time_total}s\n" \
  "http://localhost:8080/api/file/list?page=50&size=20"
```

---

## 测试 5：JVM 内存观察

```bash
# 在另一个终端中，启动应用后获取 PID
JAVA_PID=$(pgrep -f "file-processor" | head -1)

# 每 2 秒输出 GC 统计
jstat -gc "$JAVA_PID" 2000

# 或使用 jconsole 图形化观察
jconsole "$JAVA_PID"
```

在压测过程中观察：
- `OU`（Old Generation Usage）：是否持续增长（内存泄漏信号）
- `FGC`（Full GC Count）：是否频繁 Full GC
- `YGCT` / `FGCT`：GC 耗时是否影响请求延迟

---

## 测试报告

完成测试后，将结果填入 `docs/performance-report.md` 模板。

**必须遵守**：
- 不虚构数据：所有数值必须是真实测试结果。
- 标注测试环境：CPU 型号、内存大小、Docker 资源限制。
- 如果某项测试未执行，保留 `[ ]` 占位符，不要猜测填值。

---

## 变更记录

| 日期 | 说明 |
|------|------|
| 2026-07-16 | 创建测试指南 |
