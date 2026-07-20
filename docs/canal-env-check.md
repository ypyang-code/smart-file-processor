# Phase 3 闭环 2：Canal 环境与 binlog 验证

> **项目**：Smart File Processor · Phase 3
> **闭环**：2/7 — Canal 环境搭建与 binlog 验证
> **创建日期**：2026-07-15
> **状态**：设计中（Docker 配置已就绪，MySQL 运行时状态待用户验证）
> **前置闭环**：[闭环 1：设计文档](../docs/canal-sync-design.md) ✅
> **后续闭环**：闭环 3：Java Canal Client 接收 file_info 变更并打印日志

---

## 目录

1. [本闭环目标](#1-本闭环目标)
2. [当前环境审计结果](#2-当前环境审计结果)
3. [Canal Server Docker 方案](#3-canal-server-docker-方案)
4. [MySQL Canal 用户与权限](#4-mysql-canal-用户与权限)
5. [file_info binlog 验证步骤](#5-file_info-binlog-验证步骤)
6. [本闭环不做什么](#6-本闭环不做什么)
7. [问题与风险](#7-问题与风险)
8. [验收标准](#8-验收标准)

---

## 1. 本闭环目标

| 目标 | 说明 |
|:--|:--|
| 确认 MySQL binlog 已开启 | 检查 `log_bin` = ON |
| 确认 `binlog_format` = ROW | 这是 Canal 的硬性要求 |
| 确认 `server_id` 已配置 | Canal 作为 Slave 需要唯一 server_id |
| 准备 Canal Server Docker 配置 | 提供一键启动的 `docker-compose.yml` + `instance.properties` |
| 验证 Canal Server 能连接 MySQL | Docker 容器内能收到 `file_info` 表变更的 binlog 事件 |
| 输出环境验证文档 | 本文档 |

**本闭环明确不做**：
- ❌ 不写 Java Canal Client 代码
- ❌ 不修改 `build.gradle`（不引入 Canal 客户端依赖）
- ❌ 不修改 `FileUploadConsumer` / `SearchController` / 任何 Java 文件
- ❌ 不做 ES 同步
- ❌ 不移除 Consumer 中直接写 ES 的逻辑
- ❌ 不做对账补偿

---

## 2. 当前环境审计结果

### 2.1 审计日期与环境

| 项目 | 值 |
|:--|:--|
| 审计日期 | 2026-07-15 |
| 操作系统 | Windows 11 Home 10.0.26200 |
| MySQL 安装方式 | 本地 Windows 服务（非 Docker） |
| MySQL 版本 | **8.0.46** (Community Server) |
| MySQL 监听端口 | **3306** (0.0.0.0, TCP) |
| MySQL 客户端路径 | `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe` |
| MySQL 配置文件 | `C:\ProgramData\MySQL\MySQL Server 8.0\my.ini` |
| Docker 版本 | 29.5.3 (已安装) |
| Docker 运行状态 | **未运行**（daemon 未启动） |
| 项目数据库 | `file_processor` |
| 核心表 | `file_info`（v2，17 列 + 4 索引，详见 `docs/sql/init-file_info-v2.sql`） |

### 2.2 MySQL 配置文件审计（my.ini）

通过读取 `C:\ProgramData\MySQL\MySQL Server 8.0\my.ini`，确认以下关键配置：

| 配置项 | my.ini 行号 | 值 | 对 Canal 的影响 |
|:--|:--|:--|:--|
| `log-bin` | 139 | `"VIR825Y-bin"` | ✅ **binlog 已开启**。Canal 可以订阅 |
| `server-id` | 146 | `1` | ✅ **server_id 已配置**。Canal 自身会使用不同的 server_id（默认 1111），不冲突 |
| `binlog_format` | 未显式设置 | **MySQL 8.0 默认 = ROW** | ✅ ROW 格式是 Canal 的硬性要求，MySQL 8.0 默认为 ROW，无需额外配置 |
| `binlog_row_image` | 未显式设置 | **MySQL 8.0 默认 = FULL** | ✅ FULL 模式下 binlog 包含完整的 before/after image，Canal 能获取所有字段 |
| `binlog_row_event_max_size` | 325 | `8K` | ⚠️ 单行 binlog 事件最大 8KB。对于 `content` 大字段（MEDIUMTEXT），单行可能超过此限制，MySQL 会自动拆分。**需实测验证** |
| `authentication_policy` | 108 | `mysql_native_password,,` | ✅ 使用 `mysql_native_password` 认证插件，Canal 兼容 |
| `default-storage-engine` | 111 | `INNODB` | ✅ InnoDB 支持事务和行级 binlog |

### 2.3 需要运行时验证的项

以下配置无法仅通过 my.ini 确认，需要连接 MySQL 后执行 `SHOW VARIABLES` 验证。**请在启动 Canal Server 前完成这些检查**：

```sql
-- 连接 MySQL（替换 <password> 为实际密码）
mysql -u root -p<password>

-- 1. 确认 binlog 开启
SHOW VARIABLES LIKE 'log_bin';
-- 预期: +---------------+-------+
--        | Variable_name | Value |
--        +---------------+-------+
--        | log_bin       | ON    |
--        +---------------+-------+

-- 2. 确认 binlog 格式为 ROW
SHOW VARIABLES LIKE 'binlog_format';
-- 预期: +---------------+-------+
--        | Variable_name | Value |
--        +---------------+-------+
--        | binlog_format | ROW   |
--        +---------------+-------+

-- 3. 确认 server_id
SHOW VARIABLES LIKE 'server_id';
-- 预期: +---------------+-------+
--        | Variable_name | Value |
--        +---------------+-------+
--        | server_id     | 1     |
--        +---------------+-------+

-- 4. 确认 binlog row image
SHOW VARIABLES LIKE 'binlog_row_image';
-- 预期: +------------------+-------+
--        | Variable_name    | Value |
--        +------------------+-------+
--        | binlog_row_image | FULL  |
--        +------------------+-------+

-- 5. 查看当前 binlog 文件和位点
SHOW MASTER STATUS;
-- 预期: 返回当前正在写入的 binlog 文件名和 position

-- 6. 确认数据库存在
SHOW DATABASES LIKE 'file_processor';
-- 预期: file_processor

-- 7. 确认 file_info 表存在且有主键
USE file_processor;
DESC file_info;
-- 预期: id 列为 PRI (主键)，类型 bigint，Extra 为 auto_increment
```

### 2.4 如果 binlog_format 不是 ROW

如果运行时查询发现 `binlog_format != ROW`，需要在 `my.ini` 的 `[mysqld]` 段中添加：

```ini
[mysqld]
# ... 已有配置 ...

# Canal 前置配置：binlog ROW 格式
binlog-format=ROW

# 确保 binlog row image 为 FULL（可选，MySQL 8.0 默认 FULL）
binlog-row-image=FULL

# 如果还没有 binlog 配置，需要添加：
# log-bin=mysql-bin
# server-id=1
```

**⚠️ 修改 my.ini 后需要重启 MySQL 服务**（Windows 服务管理器或 `net stop MySQL80 && net start MySQL80`）。重启期间应用无法连接数据库。

### 2.5 审计结论

| 检查项 | 结果 | 说明 |
|:--|:--|:--|
| MySQL 版本 | ✅ 8.0.46 | 支持 ROW binlog |
| `log_bin` | ✅ 已配置 | my.ini L139: `log-bin="VIR825Y-bin"` |
| `binlog_format` | ✅ 预期 ROW | MySQL 8.0 默认 ROW，需运行时确认 |
| `server_id` | ✅ = 1 | my.ini L146 |
| `file_info` 主键 `id` | ✅ BIGINT AUTO_INCREMENT | 见 `init-file_info-v2.sql` L19 |
| Docker 可用 | ⚠️ 已安装但未运行 | 需启动 Docker Desktop 后再执行 `docker compose up` |
| Canal 前置条件 | ✅ **满足** | 所有硬性条件已具备 |

---

## 3. Canal Server Docker 方案

### 3.1 新增文件

```
docker/canal/
├── docker-compose.yml                     ← Canal Server 容器编排
└── conf/
    └── example/
        └── instance.properties            ← Canal Instance 配置
```

### 3.2 docker-compose.yml 关键配置说明

| 配置项 | 值 | 说明 |
|:--|:--|:--|
| `image` | `canal/canal-server:v1.1.7` | 与 MySQL 8.0 兼容的最新稳定版 |
| `container_name` | `canal-server` | 固定容器名，方便 `docker logs canal-server` |
| `ports` | `11111:11111` | Canal Client 连接端口（闭环 3 使用） |
| `volumes` | 挂载 `instance.properties` | 以只读方式注入配置文件，修改配置后需 `docker compose restart` |
| `JAVA_OPTS` | `-Xms256m -Xmx512m` | 开发环境 JVM 内存，Canal Server 本身很轻量 |
| `extra_hosts` | `host.docker.internal:host-gateway` | Windows Docker Desktop 通过 `host.docker.internal` 访问宿主机 MySQL |
| `restart` | `unless-stopped` | 容器异常退出时自动重启 |

### 3.3 instance.properties 关键配置说明

| 配置项 | 值 | 说明 |
|:--|:--|:--|
| `canal.instance.master.address` | `host.docker.internal:3306` | 宿主机 MySQL 地址（Windows Docker Desktop 专用） |
| `canal.instance.dbUsername` | `canal` | MySQL Canal 专用用户（需先创建，见 §4） |
| `canal.instance.dbPassword` | `canal` | Canal 用户密码 |
| `canal.instance.filter.regex` | `file_processor\\.file_info` | **只订阅 file_info 表**，忽略其他表和无关注入 |
| `canal.instance.filter.black.regex` | （空） | 无黑名单 |
| `canal.instance.tsdb.enable` | `true` | 启用位点持久化（H2 内嵌数据库），Canal 重启后从上次位点继续 |
| `canal.instance.gtidon` | `false` | 使用传统 binlog file + position 模式（MySQL 8.0 兼容） |

### 3.4 启动命令

```bash
# 1. 启动 Docker Desktop（Windows 任务栏图标或开始菜单）

# 2. 确认 Docker 可用
docker --version
# Docker version 29.5.3

# 3. 启动 Canal Server
cd E:\Projects\Smart-File-Processor\file-processor
docker compose -f docker/canal/docker-compose.yml up -d

# 4. 查看启动日志（等待约 30 秒）
docker logs canal-server -f

# 5. 看到以下关键日志行后，表示 Canal 启动成功：
#    - "canal started successfully"
#    - "the next step is: find [file_processor.file_info]'s position"
#    - "start successful"

# 6. 停止 Canal Server
docker compose -f docker/canal/docker-compose.yml down
```

### 3.5 Canal Server 成功启动的日志特征

启动成功后，`docker logs canal-server` 应包含以下关键行：

```
## the next step is: find [file_processor.file_info]'s position
## start successful
## the canal server is running.
```

如果看到 `ERROR` 日志包含以下内容，参考 §7 排查：

| 错误信息 | 可能原因 |
|:--|:--|
| `java.io.IOException: Connection refused` | MySQL 3306 端口不可达（Docker 网络或防火墙） |
| `Access denied for user 'canal'@'...'` | Canal 用户未创建或密码错误 |
| `Could not find first log file name in binary log index file` | MySQL binlog 未开启或 binlog 文件不存在 |
| `Unknown database 'file_processor'` | 数据库未创建 |

---

## 4. MySQL Canal 用户与权限

### 4.1 创建 Canal 专用用户

连接 MySQL 后执行以下 SQL：

```sql
-- ============================================
-- 创建 Canal 专用用户
-- 说明: Canal 需要伪装成 MySQL Slave 订阅 binlog，
--       因此需要 REPLICATION 相关权限。
-- 注意: 请先用 root 用户连接 MySQL 后执行。
-- ============================================

-- 1. 创建用户（如果已存在则跳过）
-- 注意：'canal'@'%' 允许任意主机连接。
-- 如果 MySQL 不允许远程连接，改为 'canal'@'localhost' 或 'canal'@'host.docker.internal'
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'canal';

-- 2. 授予必要权限
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';

-- 3. 刷新权限
FLUSH PRIVILEGES;

-- 4. 验证权限
SHOW GRANTS FOR 'canal'@'%';
-- 预期:
-- GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO `canal`@`%`
-- GRANT PROXY ON ... (MySQL 8.0 自动添加)
```

### 4.2 权限说明

| 权限 | 为什么需要 | 用途 |
|:--|:--|:--|
| `SELECT` | Canal 在启动时需要读取 `information_schema` 获取表结构和初始位点 | 读取数据库元数据和 binlog 状态 |
| `REPLICATION SLAVE` | Canal 伪装成 MySQL Slave，需要此权限获取 binlog dump | 订阅和接收 binlog 事件流 |
| `REPLICATION CLIENT` | 允许 `SHOW MASTER STATUS` 和 `SHOW SLAVE HOSTS` | 查看 binlog 位点和复制状态 |

### 4.3 安全提示

- 本配置中 Canal 用户密码为 `canal`，仅用于本地开发环境
- ⚠️ **生产环境请使用强密码**，通过环境变量或 Docker secrets 注入
- Canal 用户只需要 `SELECT` + `REPLICATION SLAVE` + `REPLICATION CLIENT`，**不需要** INSERT / UPDATE / DELETE / DROP 等写权限
- 如果 MySQL 配置了 `bind-address=127.0.0.1`，需要改为 `0.0.0.0` 或将 Canal 的连接地址改为 `127.0.0.1`（Docker 容器无法访问 127.0.0.1）

### 4.4 Docker 容器连接宿主机 MySQL 的网络说明

| 环境 | Canal 连接地址 | 说明 |
|:--|:--|:--|
| **Windows Docker Desktop** | `host.docker.internal:3306` | 自动 DNS 解析到宿主机 IP |
| **Linux Docker** | `host.docker.internal:3306` 或宿主机实际 IP | 需在 `docker-compose.yml` 中配置 `extra_hosts` |
| **Mac Docker Desktop** | `host.docker.internal:3306` | 同 Windows |

如果 `host.docker.internal` 解析失败，可改用宿主机的局域网 IP（如 `192.168.x.x`，通过 `ipconfig` 或 `ifconfig` 查询），并确保 MySQL 监听 `0.0.0.0`。

---

## 5. file_info binlog 验证步骤

### 5.1 前置条件

- [x] MySQL binlog 已开启
- [ ] MySQL `binlog_format=ROW` 已确认
- [ ] Canal 用户已创建
- [ ] Docker Desktop 已启动
- [ ] Canal Server 容器已运行（`docker compose up -d`）
- [ ] Canal Server 日志显示 `start successful`

### 5.2 测试数据准备

由于 `file_info` 表有较多 NOT NULL 字段，以下是最小可用 INSERT SQL。执行前请先用 `DESC file_info;` 确认字段没有变化。

```sql
USE file_processor;

-- ============================================
-- 插入一条测试记录
-- ============================================
-- file_name:  必填 (VARCHAR 255, NOT NULL)
-- file_type:  必填 (VARCHAR 20, NOT NULL)
-- file_size:  必填 (BIGINT, NOT NULL)
-- 其他字段有默认值或可为 NULL
INSERT INTO file_info (file_name, file_type, file_size)
VALUES ('canal-test-file.txt', 'text', 1024);

-- 记录生成的 id，后续 UPDATE / DELETE 会用到
-- 假设生成的 id 为 N（通过 SELECT LAST_INSERT_ID(); 获取）
SELECT LAST_INSERT_ID();
```

### 5.3 INSERT 验证

**操作**：执行上述 INSERT SQL（插入一条 file_type='text', file_size=1024 的测试记录）。

**预期 binlog 行为**：
- MySQL 将 INSERT 事件写入 binlog（ROW 格式，包含新行的完整 after-image）
- Canal Server 收到 binlog 事件，日志中出现类似输出

**预期 Canal 日志**：
```
## canal instance [example] start to find [file_processor.file_info]'s position
## find [file_processor.file_info]'s position...
```

Canal 启动时会先定位 binlog 位点。INSERT 事件的具体打印需要 Java Client 订阅后才能看到（属于闭环 3）。当前闭环只验证 Canal Server **能连接 MySQL 并开始接收 binlog**。

**验收标准**：
- [ ] Canal 日志中无 `ERROR` 和 `Access denied`
- [ ] Canal 日志中 `start successful` 已出现
- [ ] `docker logs canal-server` 的最后几行没有新的错误信息

### 5.4 UPDATE 验证

**操作**：更新测试记录的 `parse_status`。

```sql
-- 假设测试记录的 id = N
UPDATE file_info
SET parse_status = 'PARSE_SUCCESS',
    content = 'This is a test content from Canal binlog verification.',
    oss_url = 'https://oss.example.com/canal-test.txt',
    status = 2
WHERE id = N;
```

**预期 binlog 行为**：
- MySQL 将 UPDATE 事件写入 binlog（ROW 格式，包含行的 before-image 和 after-image）
- Canal Server 接收到事件，内部缓冲

**预期 Canal 日志**（如果开启了 debug 日志或使用 Canal 自带的管理界面）：
- 可以确认 event 数量在增长

**验收标准**：
- [ ] UPDATE SQL 执行成功
- [ ] `SHOW MASTER STATUS;` 的 position 发生变化（确认有新 binlog 事件写入）
- [ ] Canal Server 容器持续运行，无异常退出

### 5.5 DELETE 验证（可选）

**说明**：如果项目中 `file_info` 表不建议物理删除（当前应用确实没有删除功能），可以**跳过 DELETE 验证**。物理删除主要验证 Canal 能否捕获 DELETE 事件的 before-image。

如果选择验证：

```sql
-- 删除测试记录
DELETE FROM file_info WHERE file_name = 'canal-test-file.txt';
```

**验收标准**（可选）：
- [ ] DELETE SQL 执行成功
- [ ] `SHOW MASTER STATUS;` 的 position 再次变化

### 5.6 清理

验证完成后清理测试数据：

```sql
-- 如果测试记录还存在（未执行 DELETE），清理掉
DELETE FROM file_info WHERE file_name = 'canal-test-file.txt';
```

---

## 6. 本闭环不做什么

本闭环是 Phase 3 的**环境和基础设施验证**，明确不做以下事项：

| # | 不做 | 原因 | 归属闭环 |
|:--|:--|:--|:--|
| 1 | **不写 Java Canal Client** | 引入 Canal 客户端依赖需要修改 build.gradle，属于代码级变更，在闭环 3 中实现 | 闭环 3 |
| 2 | **不改 build.gradle** | 同上 | 闭环 3 |
| 3 | **不修改 FileUploadConsumer** | Consumer 移除 ES 写入是闭环 5 的工作，在此之前 Canal 同步和 Consumer 双写将并存 | 闭环 5 |
| 4 | **不修改 SearchController** | ES 查询方式无变化，不需要改 | 不改 |
| 5 | **不做 ES 同步** | ES 同步是闭环 4 的核心工作，需要 Canal Client + EsSyncService | 闭环 4 |
| 6 | **不移除 Consumer 中直接写 ES 的逻辑** | 属于闭环 5 | 闭环 5 |
| 7 | **不做对账补偿** | 对账是闭环 6 的工作 | 闭环 6 |
| 8 | **不新增 Java 文件** | 闭环 2 只涉及 Docker 配置和文档 | — |
| 9 | **不修改 application.yml** | Canal 配置项在闭环 3 中随 Java Client 一起添加 | 闭环 3 |

---

## 7. 问题与风险

| # | 问题 / 风险 | 影响 | 等级 | 解决方案 |
|:--|:--|:--|:--|:--|
| 1 | **MySQL binlog 未开启** | Canal 无法订阅 | 🔴 P0 | 在 my.ini 中添加 `log-bin=mysql-bin`，重启 MySQL |
| 2 | **`binlog_format` 不是 ROW** | Canal 无法解析 STATEMENT/MIXED 格式的 binlog | 🔴 P0 | `SET GLOBAL binlog_format=ROW;`（临时）+ 在 my.ini 中持久化 `binlog-format=ROW` |
| 3 | **Canal 用户权限不足** | Canal 连接 MySQL 时报 `Access denied` 或无法获取 binlog dump | 🔴 P0 | 执行 §4.1 的 GRANT 语句 |
| 4 | **Docker 容器无法连接宿主机 MySQL** | Canal Server 启动后持续报 `Connection refused` | 🔴 P0 | ① 确认 MySQL 监听 `0.0.0.0:3306`（非 `127.0.0.1`） ② 确认 Windows 防火墙允许 3306 入站 ③ 尝试将 `host.docker.internal` 改为宿主机实际 IP |
| 5 | **`content` 字段较大，binlog 传输可能有性能风险** | Canal binlog 事件大小受 `binlog_row_event_max_size`（8K）限制，大字段会自动拆分为多个事件 | 🟡 P1 | 当前每条记录生命周期内只在 PARSE_SUCCESS 时同步一次，频率极低。闭环 4 实测后如超出预期，可通过 Canal column filtering 跳过 content 列 |
| 6 | **Canal Server 能启动不等于 Java Client 已可用** | 闭环 2 只能验证 MySQL ↔ Canal Server 的链路，Canal Server ↔ Java Client 的链路在闭环 3 中验证 | 🟡 P1 | 在闭环 3 验收标准中覆盖 |
| 7 | **本闭环只证明环境可行，不证明 ES 同步正确** | Canal binlog 事件 → ES 文档的映射逻辑在闭环 4 中才实现 | 🟢 P2 | 明确标注闭环边界，避免误解 |
| 8 | **Docker Desktop 未安装或无法启动** | Canal Server 无法运行 | 🔴 P0 | Canal 依赖 Docker 部署。如果 Docker 不可用，需回退到方案 B（Outbox 表），参见 `canal-sync-design.md` §5 |

---

## 8. 验收标准

本闭环的验收标准（按检查清单格式）：

| # | 验收项 | 方法 | 状态 |
|:--|:--|:--|:--:|
| 1 | `docs/canal-env-check.md` 已新增 | 文件存在 | ✅ |
| 2 | MySQL binlog 开启状态已确认 | ✅ 2026-07-15 运行时验证: `log_bin=ON` | ✅ |
| 3 | `binlog_format=ROW` 已确认 | ✅ 2026-07-15 运行时验证: `binlog_format=ROW` | ✅ |
| 4 | Canal Server Docker 配置已就绪 | `docker/canal/docker-compose.yml` + `instance.properties` 存在 | ✅ |
| 5 | Canal 用户权限 SQL 已明确并执行 | ✅ canal@'%' 已创建，SELECT + REPLICATION SLAVE + REPLICATION CLIENT 权限已授予 | ✅ |
| 6 | file_info 表结构已验证 | ✅ `DESC file_info` 已执行，确认主键 `id` (BIGINT AUTO_INCREMENT) | ✅ |
| 7 | Canal Server 启动成功 | ❌ Docker 镜像拉取失败，Canal Server 未启动 | ❌ **阻塞** |
| 8 | 未修改 Java 代码 | ✅ 零 Java 文件改动 | ✅ |
| 9 | 未修改 build.gradle | ✅ build.gradle 未修改 | ✅ |
| 10 | 未做 ES 同步 | ✅ 无任何 ES 相关改动 | ✅ |

---

## 9. 运行时验收结果与阻塞记录

> **验收日期**：2026-07-15
> **验收执行**：Claude Code 自动化（MySQL 连接、Canal 用户创建）+ 用户提供 MySQL 密码
> **状态**：**阻塞** — Canal Server 未启动，INSERT/UPDATE/DELETE binlog 验证未执行

### 9.1 MySQL binlog 检查结果

| 检查项 | 预期值 | 实际值 | 结果 |
|:--|:--|:--|:--:|
| `log_bin` | ON | **ON** | ✅ |
| `binlog_format` | ROW | **ROW** | ✅ |
| `server_id` | > 0 | **1** | ✅ |
| `binlog_row_image` | FULL | **FULL** | ✅ |
| `SHOW MASTER STATUS` | 返回 file + position | **VIR825Y-bin.000022 / 157** | ✅ |

**结论**：MySQL 8.0.46 完全满足 Canal 前置要求。

### 9.2 file_info 表检查结果

| 检查项 | 预期 | 实际 | 结果 |
|:--|:--|:--|:--:|
| 表存在 | `file_info` 在 `file_processor` 库中 | ✅ 存在 | ✅ |
| 主键 | `id` (PRI, auto_increment) | ✅ `id` BIGINT, PRI, auto_increment | ✅ |
| 版本 | 预期 v2 (17 列) | **实际 v1 (9 列)**：id, file_name, file_type, file_size, oss_url, content, status, create_time, update_time | ⚠️ |
| 缺失字段 | upload_status, parse_status | **不存在** — Phase 2 alter-file_info-v2.sql 未执行 | ⚠️ |
| Canal 同步关键字段 | file_name, content, file_type, oss_url | ✅ 全部存在 | ✅ |

**结论**：file_info 表为 v1 schema（9 列），但 Canal 同步所需的核心字段（id, file_name, content, file_type, oss_url）全部存在。v2 字段缺失不影响 Canal 环境验证，但会影响闭环 4 的 sync mapping 设计（没有 upload_status/parse_status 字段需要同步）。

### 9.3 Canal 用户权限检查结果

```sql
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'canal';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
```

| 检查项 | 结果 |
|:--|:--:|
| canal@'%' 用户已创建 | ✅ |
| GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* | ✅ |
| MySQL 监听 0.0.0.0:3306（Docker 容器可访问） | ✅ |

### 9.4 Canal Server 启动结果

**❌ Canal Server 未启动。**

三次尝试均失败：

| 尝试 | 命令 | 结果 |
|:--|:--|:--|
| 1 | `docker compose -f docker/canal/docker-compose.yml up -d` (600s timeout) | 镜像拉取超时（多层下载中），进程被 kill |
| 2 | `docker pull canal/canal-server:v1.1.7` (600s timeout) | 4/10 层已下载，其余层 TLS handshake timeout |
| 3 | `docker pull canal/canal-server:v1.1.7` (600s timeout, 移除 mirror 后) | 同上，daemon.json 修改未生效（Docker Desktop 需重启才能应用新配置） |

**失败原因**：Docker 镜像拉取时访问 CloudFront CDN (`cloudfront-docker-cf.mrs.1ms.run`) 发生 TLS 握手超时。该 CDN 端点由原镜像站 `docker.1ms.run` 代理，网络不通。

### 9.5 INSERT / UPDATE / DELETE 验证结果

**❌ 未执行。** Canal Server 未启动，无法验证 binlog 事件是否能被 Canal 接收。

### 9.6 daemon.json 修改记录

本 session 中 `C:\Users\ASUS\.docker\daemon.json` 的 `registry-mirrors` 字段被修改：

- **原始值**（被覆盖）：
  ```json
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.xuanyuan.me"
  ]
  ```
- **当前值**：`"registry-mirrors": []`（空数组）
- **影响**：Docker Desktop 重启后将不使用任何镜像加速，直接连接 Docker Hub
- **建议**：由用户决定是否恢复原始镜像源

### 9.7 当前结论

| 闭环 2 验收项 | 状态 |
|:--|:--:|
| MySQL binlog 运行时确认 | ✅ 完成 |
| file_info 表结构确认 | ✅ 完成 |
| Canal 用户创建与权限 | ✅ 完成 |
| Canal Server 启动 | ❌ **阻塞** |
| file_info binlog 事件验证 (INSERT/UPDATE/DELETE) | ❌ **阻塞**（依赖 Canal Server 启动） |
| **Phase 3 闭环 2 整体** | ❌ **未完成** |
| **能否进入 Phase 3 闭环 3** | ❌ **不能** |

---

## 10. 后续可选方案

### 方案 A：继续 Docker，用户手动配置镜像源

**操作**：
1. 用户在 Docker Desktop 设置中配置可用镜像源（或恢复原 `docker.1ms.run` / `docker.xuanyuan.me`）
2. 重启 Docker Desktop
3. 执行 `docker pull canal/canal-server:v1.1.7`
4. 执行 `docker compose -f docker/canal/docker-compose.yml up -d`

**优点**：与已有 `docker-compose.yml` / `instance.properties` 配置完全匹配，一键启动，环境隔离好
**缺点**：依赖 Docker 镜像源可用性；镜像约 300MB，首次拉取需等待
**风险**：`docker.1ms.run` 当前 TLS 超时，需要更换可用镜像源

### 方案 B：继续 Docker，用户通过其他方式获取镜像

**操作**：
1. 用户在浏览器或其他网络环境下载 `canal/canal-server:v1.1.7` 镜像
2. `docker load < canal-server-v1.1.7.tar` 导入
3. `docker compose up -d` 启动

**优点**：绕过镜像拉取网络问题；导入完成后启动与方案 A 完全相同
**缺点**：需要用户自行获取镜像文件（~100MB 压缩包）；多一步手动操作
**风险**：中 — 取决于能否找到可用的镜像下载源

### 方案 C：放弃 Docker，Canal Server 原生包运行

**操作**：
1. 下载 `canal.deployer-1.1.7.tar.gz`（约 90MB）—— 本次尝试下载中断于 18.2MB
2. 解压到本地目录
3. 将 `docker/canal/conf/example/instance.properties` 复制到 `conf/example/`
4. Windows 下执行 `bin/startup.bat` 或直接 `java -jar` 启动

**优点**：不依赖 Docker；Canal Server 是纯 Java 应用，Java 17 环境已具备；Windows 可直接运行
**缺点**：需要额外维护 Canal Server 进程（端口、JVM 参数、日志）；不如 Docker 干净；需确认 GitHub Releases 在国内的可访问性
**风险**：GitHub Releases 下载同样可能受网络影响

### 推荐

**推荐方案 A**，理由：
1. `docker-compose.yml` + `instance.properties` 已配置完善，切换方案浪费已有工作
2. 方案 B/C 的下载源（GitHub / Docker Hub）与方案 A 相同，网络问题不会因换方案而消失
3. 方案 A 的核心问题只有一个：**Docker 镜像源可用性**。用户只需在 Docker Desktop Settings → Docker Engine 中配置一个当前可用的镜像源（如 `docker.xuanyuan.me` 或 Docker Hub 直连），重启 Docker 后即可一键启动
4. Docker Desktop 29.6.1 版本已确认可用，其余基础设施（MySQL binlog、Canal 用户、网络监听）全部就绪

**如果方案 A 不可行（镜像源持续不可用），建议方案 C**：Canal Server 本质是 Spring Boot 应用，直接 `java -jar` 运行即可，Windows 环境已具备 Java 17。

---

> **文档更新结束** · 当前阻塞在 Canal Server 启动，等待用户选择后续方案。

---

## 11. 数据库 schema 复核记录

> **复核日期**：2026-07-15
> **复核原因**：§9.2 发现 file_info 仅 9 列（v1 schema），需确认是否是项目实际使用的数据库

### 11.1 项目 datasource 配置

来源：`src/main/resources/application.yml`

```yaml
spring:
  datasource:
    url: ${MYSQL_URL:jdbc:mysql://localhost:3306/file_processor?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai}
    username: ${MYSQL_USERNAME:root}
    password: ${MYSQL_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
```

| 配置项 | 值 |
|:--|:--|
| host | `localhost` |
| port | `3306` |
| database | `file_processor` |
| username | `root`（默认值，可通过 `MYSQL_USERNAME` 环境变量覆盖） |
| password | 来自 `MYSQL_PASSWORD` 环境变量 |
| active profile | 无（仅 `application.yml`，无 `application-dev.yml` / `application-local.yml`） |

### 11.2 实际检查的 MySQL database

| 项目 | 值 |
|:--|:--|
| host | `localhost` (0.0.0.0:3306, TCP LISTENING) |
| database | `file_processor` |
| 连接用户 | `root`（用户提供的密码） |
| MySQL 版本 | 8.0.46 |

### 11.3 一致性结论

**✅ 两者完全一致。** 检查的数据库就是项目实际使用的 `file_processor`。不存在"连错数据库"的问题。

### 11.4 file_info 当前字段检查结果

执行：`USE file_processor; DESC file_info;`

| # | 字段名 | 当前类型 | 是否 v2 字段 | 状态 |
|:--|:--|:--|:--|:--|
| 1 | `id` | bigint, PRI, auto_increment | 基础字段 | ✅ |
| 2 | `file_name` | varchar(255), NOT NULL | 基础字段 | ✅ |
| 3 | `file_type` | varchar(50) | 基础字段 | ✅ |
| 4 | `file_size` | bigint | 基础字段 | ✅ |
| 5 | `oss_url` | varchar(500) | 基础字段 | ✅ |
| 6 | `content` | text | 基础字段 | ✅ |
| 7 | `status` | tinyint, DEFAULT 0 | 基础字段 | ✅ |
| 8 | `create_time` | datetime, DEFAULT CURRENT_TIMESTAMP | 基础字段 | ✅ |
| 9 | `update_time` | datetime, DEFAULT CURRENT_TIMESTAMP ON UPDATE | 基础字段 | ✅ |
| — | `file_md5` | ❌ 不存在 | **Phase 2 v2** | ❌ 缺失 |
| — | `storage_path` | ❌ 不存在 | **Phase 2 v2** | ❌ 缺失 |
| — | `upload_status` | ❌ 不存在 | **Phase 2 v2** | ❌ 缺失 |
| — | `parse_status` | ❌ 不存在 | **Phase 2 v2** | ❌ 缺失 |
| — | `total_chunks` | ❌ 不存在 | **Phase 2 v2** | ❌ 缺失 |
| — | `uploaded_chunks` | ❌ 不存在 | **Phase 2 v2** | ❌ 缺失 |
| — | `is_chunked` | ❌ 不存在 | **Phase 2 v2** | ❌ 缺失 |
| — | `merge_time` | ❌ 不存在 | **Phase 2 v2** | ❌ 缺失 |

**当前 schema：v1（9 列）。Phase 2 v2 字段全部缺失（0/8）。**

### 11.5 FileInfo.java Entity 对比

来源：`src/main/java/com/yang/fileprocessor/entity/FileInfo.java`

Entity 类**已包含全部 8 个 v2 字段**：

```java
private String fileMd5;          // L25
private String storagePath;      // L28
private String uploadStatus;     // L31
private String parseStatus;      // L34
private Integer totalChunks;     // L37
private Integer uploadedChunks;  // L40
private Boolean isChunked;       // L43
private LocalDateTime mergeTime; // L46
```

**结论**：Java Entity 预期 v2 schema，但数据库实际为 v1。这会导致 MyBatis 映射时 v2 字段全部为 null，但不会报错（MyBatis 默认宽松映射）。

### 11.6 alter-file_info-v2.sql 检查

| 项目 | 值 |
|:--|:--|
| 文件路径 | `docs/sql/alter-file_info-v2.sql` |
| 是否存在 | ✅ 存在 |
| 是否适用于当前数据库 | ✅ 是（MySQL 8.0+，数据库 `file_processor`，表 `file_info` 存在） |
| 语法兼容性 | ✅ 使用 `ADD COLUMN IF NOT EXISTS`（MySQL 8.0 支持），可重复执行 |
| 对已有数据的影响 | ✅ 无影响（所有新列有 DEFAULT 值：`DEFAULT NULL` 或 `DEFAULT 'UPLOADED'` 等） |
| 索引创建 | ⚠️ 3 条索引语句被注释（`CREATE INDEX`），需根据当前索引情况手动决定是否执行 |

**新增字段清单**：

| 字段 | 类型 | 默认值 | 用途 |
|:--|:--|:--|:--|
| `file_md5` | VARCHAR(32) | NULL | MD5 秒传/去重 |
| `storage_path` | VARCHAR(500) | NULL | 本地存储路径 |
| `upload_status` | VARCHAR(20) | 'UPLOADED' | 上传状态 |
| `parse_status` | VARCHAR(20) | 'NOT_PARSED' | 解析状态 |
| `total_chunks` | INT | 1 | 总分片数 |
| `uploaded_chunks` | INT | 0 | 已上传分片数 |
| `is_chunked` | TINYINT(1) | 0 | 是否分片上传 |
| `merge_time` | DATETIME | NULL | 合并完成时间 |

**索引检查**（当前数据库）：

```sql
SHOW INDEX FROM file_info
WHERE Key_name IN ('idx_file_md5', 'idx_upload_status', 'idx_parse_status', 'idx_status');
-- 结果：空（无匹配索引，v2 索引全部未创建）
```

当前仅存在 PRIMARY KEY (`id`)。无任何二级索引。

### 11.7 推荐修复方案

1. **执行 `docs/sql/alter-file_info-v2.sql`**：将 v1 表升级到 v2
   - 行 1-43：8 条 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 语句
   - 所有语句安全、可重复执行
2. **按需创建索引**：根据 `alter-file_info-v2.sql` L50-52 的注释提示，确定需要哪些索引后取消注释执行
3. **建议执行前备份**（虽然语句安全，但属于生产操作规范）

### 11.8 Schema 问题对 Phase 3 的影响

| 维度 | 影响 |
|:--|:--|
| Canal 环境验证 | **不阻塞**。binlog 验证只需 `id`、`file_name`、`content`、`file_type`、`oss_url`，这些字段 v1 已存在 |
| Canal → ES 同步（闭环 4） | **轻微影响**。闭环 4 的 sync mapping 需要明确 ES 文档和 MySQL 字段的对应关系。当前没有 `upload_status` / `parse_status` 字段，sync rule 设计需基于 v1 实际字段 |
| 对账补偿（闭环 6） | **无影响**。对账逻辑基于 `id` 和 `status` 字段，v1 已有 |
| 项目运行 | **不阻塞但存在风险**。FileInfo.java Entity 已定义 v2 字段，MyBatis 插入/更新可能带 v2 字段但 DB 不存在对应列 → SQL 报错。需确认当前 Mapper 的 `INSERT` 语句是否包含 v2 列 |

### 11.9 当前阻塞状态汇总

| 阻塞项 | 状态 | 归属 |
|:--|:--|:--|
| file_info v2 schema 缺失 | ⚠️ 已识别，SQL 已就绪，等待用户确认执行 | 前置修复 |
| Canal Server 镜像拉取失败 | ❌ 阻塞，等待用户选择方案（A/B/C） | Canal 环境 |
| Canal Server 启动 | ❌ 阻塞（依赖镜像拉取） | Canal 环境 |
| INSERT / UPDATE / DELETE binlog 验证 | ❌ 阻塞（依赖 Canal Server 启动） | Canal 环境 |
| daemon.json registry-mirrors 为空 | ⚠️ 需用户确认是否恢复 | Docker 配置 |
| **Phase 3 闭环 2 整体** | ❌ **未完成** | — |
| **能否进入 Phase 3 闭环 3** | ❌ **不能** | — |

### 11.10 daemon.json 当前状态

读取 `C:\Users\ASUS\.docker\daemon.json`：

```json
{
  "builder": { "gc": { "defaultKeepStorage": "20" } },
  "experimental": false,
  "registry-mirrors": []
}
```

- `registry-mirrors`：**当前为空数组 `[]`**
- **影响**：Docker Desktop 重启后，所有 `docker pull` 将直连 Docker Hub，不使用任何镜像加速
- **状态**：未修改。等待用户明确确认是否需要恢复原始镜像源后操作

---

## 12. file_info v2 schema 升级记录

> **升级日期**：2026-07-15
> **操作**：将 file_info 表从 v1 schema（9 列）升级到 Phase 2 v2 schema（17 列）

### 12.1 升级原因

- Java Entity `FileInfo.java` 已定义全部 17 个字段（含 8 个 v2 字段）
- 数据库 file_info 表仅 9 列，与 Entity 不对齐
- Canal 环境验证需要完整 schema 以避免后续 sync mapping 设计偏差
- `docs/sql/alter-file_info-v2.sql` 已就绪，可直接使用

### 12.2 备份

| 项目 | 值 |
|:--|:--|
| 备份文件 | `docs/sql/backup/file_info_backup_before_v2_20260715_214433.sql` |
| 备份方式 | `mysqldump`（结构 + 数据） |
| 备份大小 | 59,464 bytes |
| 备份时行数 | 16 行 |
| 备份状态 | ✅ 完成 |

### 12.3 执行过程

| 项目 | 值 |
|:--|:--|
| SQL 来源 | `docs/sql/alter-file_info-v2.sql`（参考） |
| 执行方式 | 逐列 `ALTER TABLE ADD COLUMN`（MySQL 8.0.46 不支持 `ADD COLUMN IF NOT EXISTS`） |
| 执行语句数 | 8 条 ALTER TABLE |
| 执行结果 | ✅ **全部成功** |

执行的 SQL：

```sql
ALTER TABLE file_info ADD COLUMN file_md5 VARCHAR(32) DEFAULT NULL;
ALTER TABLE file_info ADD COLUMN storage_path VARCHAR(500) DEFAULT NULL;
ALTER TABLE file_info ADD COLUMN upload_status VARCHAR(20) DEFAULT 'UPLOADED';
ALTER TABLE file_info ADD COLUMN parse_status VARCHAR(20) DEFAULT 'NOT_PARSED';
ALTER TABLE file_info ADD COLUMN total_chunks INT DEFAULT 1;
ALTER TABLE file_info ADD COLUMN uploaded_chunks INT DEFAULT 0;
ALTER TABLE file_info ADD COLUMN is_chunked TINYINT(1) DEFAULT 0;
ALTER TABLE file_info ADD COLUMN merge_time DATETIME DEFAULT NULL;
```

### 12.4 新增字段验证结果

| # | 字段 | 类型 | 默认值 | Null | 状态 |
|:--|:--|:--|:--|:--|:--:|
| 1 | `file_md5` | VARCHAR(32) | NULL | YES | ✅ |
| 2 | `storage_path` | VARCHAR(500) | NULL | YES | ✅ |
| 3 | `upload_status` | VARCHAR(20) | 'UPLOADED' | YES | ✅ |
| 4 | `parse_status` | VARCHAR(20) | 'NOT_PARSED' | YES | ✅ |
| 5 | `total_chunks` | INT | 1 | YES | ✅ |
| 6 | `uploaded_chunks` | INT | 0 | YES | ✅ |
| 7 | `is_chunked` | TINYINT(1) | 0 | YES | ✅ |
| 8 | `merge_time` | DATETIME | NULL | YES | ✅ |

**当前总字段数：17 列**（9 原有 + 8 新增）。

### 12.5 索引检查结果

| 索引名 | 状态 | 说明 |
|:--|:--|:--|
| PRIMARY KEY (`id`) | ✅ 存在 | 原有 |
| `idx_file_md5` | ❌ 未创建 | `alter-file_info-v2.sql` 中已注释 |
| `idx_upload_status` | ❌ 未创建 | `alter-file_info-v2.sql` 中已注释 |
| `idx_parse_status` | ❌ 未创建 | `alter-file_info-v2.sql` 中已注释 |
| `idx_status` | ❌ 未创建 | 原 v2 建表 SQL 中有，但当前数据库未创建 |

**结论**：本次升级仅新增了 8 个字段，未新增索引。索引创建留待后续按需执行。

### 12.6 旧数据默认值检查

```sql
SELECT id, file_name, status, file_md5, storage_path,
       upload_status, parse_status,
       total_chunks, uploaded_chunks, is_chunked, merge_time
FROM file_info LIMIT 5;
```

| 字段 | 预期默认值 | 实际值 | 结果 |
|:--|:--|:--|:--:|
| `file_md5` | NULL | NULL | ✅ |
| `storage_path` | NULL | NULL | ✅ |
| `upload_status` | 'UPLOADED' | 'UPLOADED' | ✅ |
| `parse_status` | 'NOT_PARSED' | 'NOT_PARSED' | ✅ |
| `total_chunks` | 1 | 1 | ✅ |
| `uploaded_chunks` | 0 | 0 | ✅ |
| `is_chunked` | 0 | 0 | ✅ |
| `merge_time` | NULL | NULL | ✅ |

**16 行已有数据，默认值全部正确。**

### 12.7 Entity 对齐状态

| 维度 | 状态 |
|:--|:--|
| FileInfo.java Entity (17 fields) | ✅ v2 |
| MySQL file_info 表 (17 columns) | ✅ v2 |
| 两者对齐 | ✅ **已对齐** |

### 12.8 已知问题

`docs/sql/alter-file_info-v2.sql` 使用了 `ADD COLUMN IF NOT EXISTS` 语法，但 **MySQL 8.0.46 不支持此语法**。实际执行时改为普通 `ADD COLUMN`。如果后续有人再次执行该文件，仍会报错。建议更新该文件以兼容实际 MySQL 版本。

### 12.9 当前 Phase 3 闭环 2 阻塞状态

| # | 阻塞项 | 状态 |
|:--|:--|:--|
| 1 | file_info v2 schema 缺失 | ✅ **已修复** |
| 2 | Canal Server 镜像拉取失败 | ❌ 阻塞 |
| 3 | Canal Server 未启动 | ❌ 阻塞 |
| 4 | INSERT/UPDATE/DELETE binlog 未验证 | ❌ 阻塞 |
| 5 | daemon.json registry-mirrors 为空 | ⚠️ 待确认 |
| **Phase 3 闭环 2 整体** | | ❌ **未完成** |

**可以继续处理 Canal Server 启动**，但需先解决 Docker 镜像拉取问题（用户选择方案 A/B/C）。

---

## 13. alter-file_info-v2.sql 兼容性修正记录

> **修正日期**：2026-07-15
> **修正原因**：§12.8 发现 MySQL 8.0.46 不支持 `ADD COLUMN IF NOT EXISTS`

### 13.1 修正内容

| 修正项 | 修正前 | 修正后 |
|:--|:--|:--|
| 语法 | `ADD COLUMN IF NOT EXISTS` | `ADD COLUMN`（普通语法） |
| 可重复执行 | ❌ 报语法错误 | ❌ 报 "Duplicate column name" |
| 文件头部说明 | 简单说明，未标注兼容性风险 | 新增 6 条执行前必读说明（检查 DESC / 判断是否已升级 / 备份提醒） |
| 索引部分 | 注释，说明不够清晰 | 增加 `SHOW INDEX` 检查指引 |

### 13.2 新增的头部安全说明

```text
1. 本脚本使用普通 ADD COLUMN（不含 IF NOT EXISTS），不可重复执行。
2. 执行前请先 DESC file_info 检查当前字段。
3. 如果已经是 17 列 → 不要执行。
4. 当前项目数据库已完成 v2 升级（2026-07-15），本文件仅作参考。
5. 建议执行前备份。
6. 新环境用 init-file_info-v2.sql，不要用本脚本。
```

### 13.3 未修改的内容

- 8 个字段定义（类型、默认值、COMMENT）— 不变
- 索引语句 — 保持注释
- init-file_info-v2.sql — 未检查，未修改
