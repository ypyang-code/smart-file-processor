# Phase 1: RabbitMQ 可靠性增强 · 工程复盘

> **项目**：Smart File Processor
> **阶段**：Phase 1 — RabbitMQ 可靠性增强
> **实际闭环**：闭环 2（Consumer MANUAL ACK + Spring Retry）→ 闭环 3（DLX/DLQ + DeadLetterConsumer）→ 闭环 4（Producer Confirm + ReturnsCallback + 消息持久化）
> **复盘日期**：2026-07-15
> **文档定位**：Phase 1 实际完成后的工程复盘，记录真实实现、设计决策和可靠性边界。与 `docs/refactor-plan.md` 区分——refactor-plan 是 Phase 1 的原始计划（包含未实现的部分），本文档是实际实现状态。

---

## 目录

1. [背景与问题分析](#1-背景与问题分析)
2. [Phase 1 设计边界](#2-phase-1-设计边界)
3. [完整链路图](#3-完整链路图)
4. [闭环 2：Consumer MANUAL ACK + Spring Retry](#4-闭环-2consumer-manual-ack--spring-retry)
5. [闭环 3：DLX/DLQ + DeadLetterConsumer](#5-闭环-3dlxdlq--deadletterconsumer)
6. [闭环 4：Producer Confirm + ReturnsCallback + 消息持久化](#6-闭环-4producer-confirm--returnscallback--消息持久化)
7. [可靠性边界总结](#7-可靠性边界总结)
8. [面试表达版本](#8-面试表达版本)
9. [简历项目描述版本](#9-简历项目描述版本)
10. [后续阶段建议](#10-后续阶段建议)
11. [与现有文档的关系](#11-与现有文档的关系)

---

## 1. 背景与问题分析

### 1.1 Phase 0 原始链路

Phase 0 交付了一条"能跑"的异步处理链路：

```
用户上传文件
  → FileController.upload()
    ├── INSERT file_info (status=0)
    ├── 暂存本地磁盘 uploads/{id}_{name}
    ├── FileUploadProducer.sendUploadTask(msg)
    │     └── rabbitTemplate.convertAndSend("file.upload.queue", message)
    │         使用默认交换机 (direct, routingKey=队列名)
    │         无 CorrelationData, 无 ConfirmCallback
    │         deliveryMode 默认值 (非持久化)
    └── return "文件已提交处理"

  → FileUploadConsumer.handleFileUpload(msg)   (@RabbitListener, AUTO ACK)
    ├── OSS 上传
    ├── 文本提取 (PDFBox / POI)
    ├── UPDATE file_info (status=2, content, ossUrl)
    ├── ES index
    └── 删除临时文件
    catch(Exception) → UPDATE status=3, 记录异常 → 消息已 AUTO ACK 无法重试
```

**总代码量**：`RabbitMqConfig.java`（单队列声明 + 默认 RabbitTemplate）+ `FileUploadProducer.java`（一行 `convertAndSend`）+ `FileUploadConsumer.java`（AUTO ACK + 统一 catch）。

### 1.2 为什么"能跑但不可靠"

Phase 0 链路在**所有中间件正常**时可以完成端到端流程，但在**任何一个异常场景**下都可能导致消息静默丢失，且没有任何机制发现或恢复。问题出在三个层面：

- **Producer 端**：发送后无确认、无法感知路由失败、消息未显式标记持久化
- **Broker 端**：无可恢复性配置（消息非持久化、无死信路由）
- **Consumer 端**：AUTO ACK 导致业务未完成但消息已删、异常不区分可重试/不可重试、失败消息无兜底

### 1.3 升级前 7 类具体风险

| # | 风险 | 触发场景 | 后果 |
|:--|:--|:--|:--|
| 1 | **Producer 发送后无确认** | `convertAndSend` 方法返回不抛异常，但消息可能因网络抖动未到达 Broker | 消息丢失，Producer 不知情，DB 中 file_info 永久 status=0 |
| 2 | **消息无法路由不可见** | routingKey 拼写错误、队列未声明 → Broker 丢弃消息 | 无日志、无告警，消息被 Broker 静默丢弃（默认交换机不支持 mandatory/return） |
| 3 | **消息未显式持久化** | `deliveryMode` 默认为非持久化，Broker 重启后内存中的消息全部丢失 | 已发送但未消费的消息永久丢失，DB 中 status=0 但消息已不存在 |
| 4 | **Consumer AUTO ACK 导致业务未完成但消息已删除** | 消费者拿到消息后 RabbitMQ 立即 ACK 删除，Consumer 进程在此之后崩溃 | OSS 未上传、ES 未索引、文件永久丢失，无恢复手段 |
| 5 | **Consumer 异常不区分可重试/不可重试** | 所有异常统一 catch → 写 status=3 → 消息已 AUTO ACK | DB 连接临时断开被标记为永久失败，OSS 上传超时被标记为永久失败——这些本应重试 |
| 6 | **失败消息无 DLQ 沉淀** | `requeue=false` 或异常后消息直接丢弃 | 无统一失败视图，排查依赖散落的 ERROR 日志，无法快速回答"有哪些文件处理失败了" |
| 7 | **Phase 2 分片合并后强依赖 MQ 触发解析** | `ChunkUploadService.mergeChunks()` 合并完成后通过 MQ 发送消息触发 Consumer 解析 | 如果 MQ 不可靠，合并成功但消息丢失 → 用户看到"上传成功"但文件永不解析 |

---

## 2. Phase 1 设计边界

Phase 1 的定位是**让 RabbitMQ 链路从"发出去不管"升级到"发送可确认、路由可感知、消息可持久、消费可重试、失败可追踪"**。

这不是"生产级终极方案"，而是**可靠性雏形**——先让消息链路在异常场景下不再静默丢消息，更高阶的保障（事务消息、幂等、自动补偿）属于后续阶段的深化方向。

### 2.1 本阶段明确不做

| 不做 | 原因 | 归属阶段 |
|:--|:--|:--|
| 消息发送任务表（`mq_sent_record`） | 需要新表 + 定时轮询 + 幂等消费配合，设计复杂 | Phase 3 |
| 自动补偿重发（`TaskCompensationService`） | 同上，且盲目重发可能放大故障 | Phase 3 |
| Consumer 幂等消费表（`mq_consumed_record`） | 当前业务重复消费"碰巧幂等"（覆盖写），可延后 | Phase 3 |
| 分布式事务（Seata / 事务消息） | 引入复杂度远超出单体项目定位 | 不做 |
| MySQL ↔ ES 最终一致性（Canal / Outbox） | 需要额外基础设施 | Phase 3 |
| 死信自动重投 | 自动重投可能让故障消息反复进出 DLQ | 不做 |
| 消息积压告警 | 当前通过 RabbitMQ Management UI 观察 | Phase 3/4 |

### 2.2 与原始计划的差异

`docs/refactor-plan.md` 中 Phase 1 原始计划包含以下内容，但实际实现中**刻意未做**：

| 原始计划内容 | 实际实现 | 原因 |
|:--|:--|:--|
| `TaskRetryLog` 表 + `TaskRetryLogMapper` | 未做 | 补偿任务应基于消息发送记录表设计，而非单独的重试日志表 |
| `TaskCompensationService` 定时扫描重试 | 未做 | 需配合幂等消费表使用，延后到 Phase 3 |
| `RetryableException` / `FatalException` 自定义异常类 | 未做 | 使用标准异常（`FileNotFoundException` vs `Exception`）区分即可，减少类膨胀 |
| `FileUploadMessage.retryCount` 字段 | 未做 | Spring Retry 内部维护重试计数，消息体不需要携带 |
| `FileInfoService.updateStatus()` 状态机校验 | 未做 | 当前状态机简单（0→2/3），不需要运行时校验 |
| `MqRetryConfig.java` 独立配置类 | 未做 | 全部在 `application.yml` 中通过 `spring.rabbitmq.listener.simple.retry.*` 配置 |

这些是**有意的减法**，不是遗漏。

### 2.3 实际改动范围

| 闭环 | 修改文件 | 新增文件 | 改动行数 |
|:--|:--|:--|:--|
| 闭环 2 | `FileUploadConsumer.java` (重构), `application.yml` (新增 listener 配置), `build.gradle` (新增 spring-retry), `README.md` | — | ~80 行 |
| 闭环 3 | `RabbitMqConfig.java` (新增 4 个常量 + 4 个 Bean) | `DeadLetterConsumer.java` | ~80 行 |
| 闭环 4 | `RabbitMqConfig.java` (新增 2 个常量 + 2 个 Bean + RabbitTemplate 重构), `FileUploadProducer.java` (重写), `application.yml` (新增 publisher 配置), `README.md` | — | ~100 行 |

**未改动文件**：`FileController.java`、`ChunkUploadService.java`、`FileUploadMessage.java`、`FileInfoService.java`、`FileInfoMapper.java`——所有上游调用方零改动兼容。

---

## 3. 完整链路图

### 3.1 正常路径

```
┌─────────────────────────────────────────────────────────────────┐
│ PRODUCER                                                        │
│                                                                  │
│ FileController.upload()         ChunkUploadService.mergeChunks()│
│        │                                    │                    │
│        └──────────────┬─────────────────────┘                    │
│                       ▼                                          │
│         FileUploadProducer.sendUploadTask(msg)                   │
│                       │                                          │
│                       │ correlationId = "file-upload:{fileId}:   │
│                       │                {8位uuid}"                │
│                       │ deliveryMode = PERSISTENT                │
│                       │ mandatory = true                         │
│                       ▼                                          │
│           rabbitTemplate.convertAndSend(                         │
│               "file.upload.exchange",     ← DirectExchange       │
│               "file.upload.routing.key",  ← routingKey           │
│               message, postProcessor, correlationData)           │
│                       │                                          │
│      ┌────────────────┼────────────────┐                        │
│      ▼                ▼                ▼                         │
│ ConfirmCallback  ReturnsCallback   AmqpException                 │
│ (ack=true→INFO)  (正常时不触发)    (网络不通→向上抛)              │
└──────────────────────────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│ BROKER                                                           │
│                                                                   │
│ file.upload.exchange ──[binding]──▶ file.upload.queue            │
│ (durable)                           (durable, DLX 绑定)           │
└──────────────────────────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│ CONSUMER                                                          │
│                                                                   │
│ FileUploadConsumer.handleFileUpload(msg, channel, deliveryTag)   │
│                       │                                           │
│        ┌──────────────┼──────────────┐                           │
│        ▼              ▼              ▼                            │
│   成功路径       不可重试异常     可重试异常                       │
│                                                                   │
│   OSS 上传      FileNotFound    OSS/DB/ES/IO 临时故障            │
│   文本提取          │                 │                            │
│   DB status=2   basicNack        updateStatus(3)                 │
│   ES 索引       (requeue=false)  throw RuntimeException          │
│   删临时文件         │                 │                            │
│   basicAck          │           Spring Retry (最多3次)            │
│                     │           2s → 4s 退避                      │
│                     │                 │                            │
│                     │          ┌──────┴──────┐                    │
│                     │          ▼              ▼                    │
│                     │      重试成功      3次全败                   │
│                     │      basicAck   Container basicNack         │
│                     │                 (requeue=false)             │
│                     │                      │                       │
│                     ▼                      ▼                       │
│              ┌───────────────────────────────────┐               │
│              │  DLX: file.upload.dlx.exchange     │               │
│              │          (direct, durable)         │               │
│              │            │                       │               │
│              │            ▼                       │               │
│              │  DLQ: file.upload.dlq              │               │
│              │          (durable)                 │               │
│              │            │                       │               │
│              │            ▼                       │               │
│              │  DeadLetterConsumer                │               │
│              │  ├─ 提取 x-death header           │               │
│              │  ├─ log.warn("【死信消息】")      │               │
│              │  ├─ DB: status=3 + parse_status    │               │
│              │  │   =PARSE_FAILED + 死因          │               │
│              │  └─ finally: channel.basicAck     │               │
│              └───────────────────────────────────┘               │
└──────────────────────────────────────────────────────────────────┘
```

### 3.2 七条失败分支

| # | 分支 | 触发条件 | 消息去向 | 日志关键字 | DB 状态 |
|:--|:--|:--|:--|:--|:--|
| ① | **AmqpException** | `convertAndSend` 同步阶段网络不通 / Broker 不可达 | 未发出 | `ERROR 消息发送异常` → 向上抛 | 调用方决定：Controller 返回 500 / ChunkMerge 返回"需手动补发" |
| ② | **Confirm ack=false** | Broker 收到但 Exchange 不存在 | Broker 已拒绝 | `ERROR 消息未被Broker确认接收` | 文件保持初始状态（status=0 / WAITING_PARSE） |
| ③ | **ReturnsCallback** | Exchange 存在但 routingKey 无匹配绑定 | Broker 回退消息 | `ERROR 消息无法路由：replyCode=312` | 同上 |
| ④ | **Consumer 不可重试异常** | `FileNotFoundException`（文件被误删/路径错误） | basicNack(requeue=false) → DLQ | `ERROR 文件不存在，丢弃消息` → `WARN 【死信消息】` | status=3 + parse_status=PARSE_FAILED（含死因） |
| ⑤ | **Consumer 可重试异常 → 恢复** | OSS/DB/ES 临时故障，第 1-2 次重试恢复 | basicAck 正常确认 | `ERROR 文件处理失败，将触发重试` → `INFO 文件处理成功并确认` | 最终 status=2 + PARSE_SUCCESS |
| ⑥ | **Consumer 可重试异常 → 3 次全败** | 持续性故障（OSS 密钥过期、DB 宕机） | Container basicNack(requeue=false) → DLQ | 3 次 `ERROR` → `WARN 【死信消息】` | status=3 + PARSE_FAILED（含死因） |
| ⑦ | **DeadLetterConsumer 处理死信** | 消息已进入 DLQ | DeadLetterConsumer basicAck 删除 | `WARN 【死信消息】` | status=3 + PARSE_FAILED + 死因 |

---

## 4. 闭环 2：Consumer MANUAL ACK + Spring Retry

### 4.1 为什么 AUTO ACK 不可靠

AUTO ACK 模式下，RabbitMQ 把消息推给 Consumer 后**立即确认删除**，此时业务代码尚未开始执行。如果 Consumer 进程在拿到消息后、业务处理完成前崩溃：

```
Broker 推送消息 → Consumer 收到 → 立即 AUTO ACK (消息从队列删除)
                                  → 开始 OSS 上传...
                                  → 💥 进程崩溃
                                  → OSS 未上传完成
                                  → DB 未更新
                                  → 消息已从 Broker 永久删除
                                  → 文件永远丢失
```

MANUAL ACK 的核心原则：**只有业务全部成功完成，才调 `channel.basicAck()` 确认消息。** 在此之前消息在 Broker 中保持 unacked 状态，Channel 关闭时自动重新入队。

### 4.2 三路径异常处理

```java
@RabbitListener(queues = "file.upload.queue")
public void handleFileUpload(msg, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
    try {
        // 0. parse_status → PARSING
        // 1. OSS 上传 (InputStream → OSS)
        // 2. 文本提取 (PDFBox/POI)
        // 3. DB 更新 (status=2, content, ossUrl, parse_status=PARSE_SUCCESS)  ← @Transactional
        // 4. ES 索引
        // 5. 删除临时文件
        // 6. 手动 ACK
        channel.basicAck(deliveryTag, false);

    } catch (FileNotFoundException e) {
        // ★ 路径 2：不可重试 — 文件不存在，重试多少次都没用
        fileInfoService.updateStatus(fileId, 3, "文件不存在: " + e.getMessage(), null);
        channel.basicNack(deliveryTag, false, false);  // requeue=false → DLQ
        return;  // 不抛异常，Spring Retry 不触发

    } catch (Exception e) {
        // ★ 路径 3：可重试 — OSS 超时 / DB 断开 / ES 不可用 / IO 异常
        fileInfoService.updateStatus(fileId, 3,
                "处理失败(将重试): " + e.getClass().getSimpleName() + " - " + e.getMessage(), null);
        // ★ 不调 basicAck / basicNack
        // → Spring Retry 拦截 RuntimeException
        // → 按配置重试（2s → 4s，最多尝试 3 次）
        // → 3 次全败 → Container + default-requeue-rejected=false
        //   → Container 调用 basicNack(requeue=false) → 进入 DLQ
        throw new RuntimeException("文件处理失败，触发Spring Retry重试", e);
    }
}
```

### 4.3 basicAck / basicNack IOException 处理

`basicAck` 和 `basicNack` 可能与 Broker 通信失败而抛 `IOException`。所有 IOException 在内部 catch，不向外抛：

- **成功路径中 basicAck 失败**：业务已全部完成（DB=2, OSS/ES 已写入），消息保持 unacked。Channel 关闭后重新投递 → Consumer 再次处理 → OSS 覆盖上传、DB 同值 UPDATE、ES 覆盖索引。**这是幂等的**，所以不需要让 basicAck IOException 触发 Spring Retry。
- **失败路径中 basicNack 失败**：DB 已记录失败状态。消息保持 unacked，Channel 关闭后重新投递 → 再次进入 FileNotFoundException → 再次尝试 basicNack。不向外抛，避免 Spring Retry 错误介入。

### 4.4 Spring Retry 配置

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        acknowledge-mode: manual          # 手动 ACK
        prefetch: 1                       # 每次只取 1 条消息
        default-requeue-rejected: false   # Container Nack 时 requeue=false → 进入 DLQ
        retry:
          enabled: true
          max-attempts: 3                 # 首次消费 1 次 + 重试 2 次 = 最多 3 次尝试
          initial-interval: 2000ms        # 首次重试前等 2 秒
          multiplier: 2                   # 指数退避：2s → 4s（下次 8s 但被 max-interval 截断）
          max-interval: 10000ms           # 单次等待上限 10 秒
```

**关键参数解释**：

- `max-attempts: 3`：包含首次消费，实际重试 2 次。总共最多执行 3 次业务逻辑。
- `prefetch: 1`：确保重试期间不会拉取下一条消息，避免消息顺序问题。
- `default-requeue-rejected: false`：Spring Retry 耗尽后，Container 执行 `basicNack(requeue=false)`，消息进入 DLQ 而不是回到队列尾部（无限循环）。
- Spring Retry 是**消费者线程内阻塞重试**（不是重新投递到队列），优势是不消耗额外的网络往返，劣势是重试期间 Consumer 线程被占用。

### 4.5 解决了什么

- ✅ Consumer 崩溃不再丢消息（unacked 消息在 Channel 关闭后重新入队）
- ✅ 临时故障（DB 连接断开、ES 超时、OSS 网络抖动）自动恢复
- ✅ 不可重试故障（文件不存在）立即 Nack，不浪费重试次数
- ✅ 每次重试前刷新 DB 失败标记，可追踪最新失败原因

### 4.6 没有解决什么

- ❌ 没有消息消费幂等表——重复消费时 OSS 文件被覆盖、ES 文档被覆盖。当前"碰巧幂等"，但浪费资源
- ❌ 重试期间 Consumer 线程阻塞，极端场景下吞吐量下降到 1/3
- ❌ 重试耗尽后 Container Nack，但闭环 2 时期尚无 DLX/DLQ——闭环 3 补上了这个缺口

---

## 5. 闭环 3：DLX/DLQ + DeadLetterConsumer

### 5.1 为什么需要死信队列

闭环 2 实现了 MANUAL ACK + 重试，但有两个产生死信的路径：

1. Consumer 主动 `basicNack(requeue=false)`（FileNotFoundException）
2. Container 在重试耗尽后 `basicNack(requeue=false)`（可重试异常 3 次全败）

在没有 DLX/DLQ 的过渡状态下，这些被 Nack 的消息被 RabbitMQ **直接丢弃**。DB 中虽有 status=3 的记录，但消息本体（fileId、fileName、filePath、x-death 死因信息）永久消失，排查只能靠猜。

死信队列的作用：**把"被拒绝的消息"自动路由到一个专门的保留队列**，由 DeadLetterConsumer 统一处理（记录日志 + 写入 DB），然后 ACK 删除。排查人员通过日志和 DB 即可定位每条死信，不需要怀疑"是不是消息根本没发出来"。

### 5.2 file.upload.queue 如何绑定 DLX

```java
// 业务队列声明时通过 QueueBuilder 携带 DLX 参数
@Bean
public Queue fileUploadQueue() {
    return QueueBuilder.durable("file.upload.queue")
            .deadLetterExchange("file.upload.dlx.exchange")       // 死信交换机
            .deadLetterRoutingKey("file.upload.dlq.routing.key")  // 死信路由键
            .build();
}

// 死信交换机 (DirectExchange, durable)
@Bean
public DirectExchange fileUploadDlxExchange() {
    return new DirectExchange("file.upload.dlx.exchange", true, false);
}

// 死信队列 (durable)
@Bean
public Queue fileUploadDlq() {
    return new Queue("file.upload.dlq", true);
}

// 绑定：DLX ← DLQ
@Bean
public Binding fileUploadDlqBinding() {
    return BindingBuilder.bind(fileUploadDlq())
            .to(fileUploadDlxExchange())
            .with("file.upload.dlq.routing.key");
}
```

> ⚠️ **部署注意**：如果 RabbitMQ 中已存在旧版 `file.upload.queue`（无 DLX 参数），修改声明会导致 `PRECONDITION_FAILED`。部署前需先删除旧队列：`rabbitmqctl delete_queue file.upload.queue`。

### 5.3 哪些消息会进入 DLQ

- Consumer 主动 `basicNack(requeue=false)`（FileNotFoundException 等不可重试异常）
- Container 重试耗尽后 `basicNack(requeue=false)`（可重试异常 3 次全败）
- 消息 TTL 过期（当前未设置 per-message TTL，无此场景）
- 队列长度超限（当前未设置 max-length，无此场景）

### 5.4 x-death header 的作用

当消息被拒绝或过期后路由到 DLQ 时，RabbitMQ 自动在消息上添加 `x-death` header。它是一个数组，每条记录包含：

| 字段 | 含义 | 示例值 |
|:--|:--|:--|
| `reason` | 死因 | `"rejected"`（被拒绝）、`"expired"`（过期）、`"maxlen"`（超长） |
| `queue` | 原始来源队列 | `"file.upload.queue"` |
| `exchange` | 原始来源交换机 | `"file.upload.exchange"`（闭环 4 后）或 `""`（默认交换机） |
| `routing-keys` | 原始 routingKey | `"file.upload.routing.key"` |
| `time` | 死亡时间 | `2026-07-15T14:30:00.000+08:00` |
| `count` | 被拒绝次数 | `3` |

**关于 `originalQueue` 来源**：`@Header(CONSUMER_QUEUE)` 拿到的是死信队列本身（`file.upload.dlq`），不是原始业务队列。原始队列必须从 `x-death[0].queue` 提取。

### 5.5 DeadLetterConsumer 设计

```java
@Service
public class DeadLetterConsumer {

    @RabbitListener(queues = "file.upload.dlq")
    public void handleDeadLetter(FileUploadMessage message, Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(AmqpHeaders.CONSUMER_QUEUE) String dlqConsumerQueue,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String receivedRoutingKey,
            Message amqpMessage) {

        try {
            // 1. 提取 x-death header
            List<Map<String, ?>> xDeath = getXDeath(amqpMessage);

            // 2. 提取原始来源队列（x-death[0].queue）
            String originalQueue = extractOriginalQueue(xDeath);

            // 3. 格式化死因
            String deathReason = formatDeathReason(xDeath);

            // 4. 记录日志（WARN 级别：这是预期的业务事件，不是系统错误）
            log.warn("【死信消息】fileId={}, fileName={}, filePath={}, " +
                      "dlqConsumerQueue={}, originalQueue={}, receivedRoutingKey={}, " +
                      "deathReason={}, xDeath={}",
                      fileId, fileName, filePath,
                      dlqConsumerQueue, originalQueue, receivedRoutingKey,
                      deathReason, xDeath);

            // 5. 更新 DB（追加死因）
            fileInfoService.updateStatus(fileId, 3,
                    "消息进入死信队列: " + deathReason, null);

        } catch (Exception e) {
            // ★ 内部 catch 所有异常，不允许向外抛
            // 否则可能触发 Spring Retry 导致 DLQ 消息重复消费
            log.error("DeadLetterConsumer 处理异常(消息仍会被ACK): ...", e);
        } finally {
            // ★ 无论如何都要 ACK，防止 DLQ 堆积
            try {
                channel.basicAck(deliveryTag, false);
            } catch (IOException ackEx) {
                log.error("DeadLetterConsumer basicAck IO异常: ...", ackEx);
            }
        }
    }
}
```

### 5.6 为什么 DeadLetterConsumer 最终要 ACK

死信消息的价值在于**被记录**，不在于**被保留在 Broker 中**。日志（`【死信消息】`关键字 + x-death 详情）和 DB（status=3 + parse_status=PARSE_FAILED + 死因）已经写入了所有排查所需信息。消息本体在 Broker 中没有额外价值，继续留在 DLQ 只会堆积并占用内存/磁盘。

设计哲学：**死信即归档**。消费 → 记录 → 确认删除。后续排查不需要登录 RabbitMQ Management UI，直接看应用日志和 DB 即可。

### 5.7 解决了什么

- ✅ 失败消息不再被静默丢弃
- ✅ 所有死信有统一日志格式（`【死信消息】`），便于 grep / ELK 集中查看
- ✅ DB 中记录死因（`parse_status=PARSE_FAILED` + `"消息进入死信队列: reason=rejected, queue=file.upload.queue, time=..."`）
- ✅ DLQ 不堆积（每条死信被处理后立即 ACK）
- ✅ 排查不再需要猜测"消息是不是根本没发出来"

### 5.8 没有解决什么

- ❌ 死信不会自动重投到业务队列——需人工排查根因后手动重新上传或通过管理界面补发
- ❌ 死信在 DB 中的记录只有一条 `updateStatus()` 覆盖写，不会保留完整死信历史
- ❌ 没有死信告警（DLQ 深度超过阈值时不会主动通知）

---

## 6. 闭环 4：Producer Confirm + ReturnsCallback + 消息持久化

### 6.1 为什么不继续使用默认交换机直接发送

Phase 0 使用 `rabbitTemplate.convertAndSend("file.upload.queue", message)`——发送到**默认交换机**（direct, nameless exchange `""`），routingKey 即队列名。

这种方式的限制：

1. **默认交换机不支持 `mandatory` + `ReturnsCallback`**：消息如果因 routingKey 错误无法路由，Broker 直接丢弃，不会回退到 Producer。这是 AMQP 0-9-1 协议层面的限制。
2. **无法扩展路由逻辑**：如果以后需要按文件类型路由到不同队列（PDF 走一个队列，Word 走另一个），默认交换机做不到。
3. 缺少明确的 exchange 名称，排查时不直观。

### 6.2 为什么新增业务交换机 `file.upload.exchange`

```java
// 常量声明
public static final String FILE_UPLOAD_EXCHANGE   = "file.upload.exchange";
public static final String FILE_UPLOAD_ROUTING_KEY = "file.upload.routing.key";

// Exchange 声明
@Bean
public DirectExchange fileUploadExchange() {
    return new DirectExchange(FILE_UPLOAD_EXCHANGE, true, false);  // durable, not auto-delete
}

// Binding: Exchange → Queue
@Bean
public Binding fileUploadBinding() {
    return BindingBuilder.bind(fileUploadQueue())
            .to(fileUploadExchange())
            .with(FILE_UPLOAD_ROUTING_KEY);
}
```

**为什么选择 DirectExchange 而不是 TopicExchange**：当前只有一种消息类型（文件上传任务），一个 routingKey 对应一个队列。DirectExchange 精确匹配即可，不需要 TopicExchange 的通配符路由。未来如有多种消息类型可平滑升级。

**为什么必须使用命名交换机**：`mandatory=true` + `ReturnsCallback` 只在命名交换机上生效。默认交换机收到带 mandatory 标志的消息后，如果队列不存在，不会回退，只会丢弃，因为默认交换机没有 return 路径。

### 6.3 routingKey 的作用

`file.upload.routing.key` 是消息从 `file.upload.exchange` 路由到 `file.upload.queue` 的匹配键。它和队列名不同，具备独立语义——队列名是物理资源标识，routingKey 是逻辑路由规则。如果后续增加队列（如 `file.upload.high-priority.queue`），只需新增 binding（不同 routingKey → 不同队列），不需要改 Producer 代码。

### 6.4 ConfirmCallback 的边界

```java
rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
    if (ack) {
        log.info("消息已被Broker确认接收：correlationId={}", correlationData.getId());
    } else {
        log.error("消息未被Broker确认接收：correlationId={}, cause={}",
                  correlationData.getId(), cause != null ? cause : "未知原因");
    }
});
```

**边界说明**：

| Confirm 结果 | 含义 | 不代表 |
|:--|:--|:--|
| `ack=true` | Broker 接收了 `basic.publish`，Exchange 存在且接受了消息 | ❌ 不代表消息进入了队列（routingKey 可能无匹配） |
| `ack=false` | Broker 拒绝了消息（Exchange 不存在 / 内部错误） | — |

**典型场景**：
- routingKey 正确 + 队列绑定存在 → `ack=true`，ReturnsCallback 不触发
- routingKey 错误 + 无匹配队列 → `ack=true`（！）+ ReturnsCallback **触发**
- Exchange 不存在 → `ack=false`

日志文案严格区分："消息已被 Broker 确认接收"（不是"已进入队列"），避免误导。

### 6.5 ReturnsCallback 的边界

```java
rabbitTemplate.setMandatory(true);  // ★ 必须设置
rabbitTemplate.setReturnsCallback(returned -> {
    log.error("消息无法路由：replyCode={}, replyText={}, exchange={}, routingKey={}, " +
              "correlationId={}, deliveryMode={}, bodyLength={}",
              returned.getReplyCode(), returned.getReplyText(),
              returned.getExchange(), returned.getRoutingKey(),
              correlationId,
              returned.getMessage().getMessageProperties().getDeliveryMode(),
              returned.getMessage().getBody().length);
    // ★ 不打印 body 内容：避免敏感数据泄露和日志膨胀
});
```

**关键认知**：

- **错误 routingKey 通常触发 ReturnsCallback，不触发 Confirm ack=false**。因为 Exchange 存在时 Broker 先返回 ack=true，再异步回退无法路由的消息。
- **Exchange 不存在**时才出现 `ack=false`（或同步抛出 `AmqpException`）。
- ReturnsCallback 只在 `mandatory=true` 时触发。只配置 `publisher-returns=true`（YAML）而不设置 `setMandatory(true)`（Java），ReturnsCallback 永远不会被调用。

**ReturnsCallback 日志包含**：replyCode（如 312 NO_ROUTE）、replyText、exchange、routingKey、correlationId、deliveryMode、bodyLength。不包含 message body 内容——避免敏感数据和日志膨胀。

### 6.6 CorrelationData / correlationId

```java
String correlationId = "file-upload:" + message.getFileId()
        + ":" + UUID.randomUUID().toString().substring(0, 8);
CorrelationData correlationData = new CorrelationData(correlationId);
```

**作用 1：关联发送与确认**。ConfirmCallback 带回 `correlationData.getId()`，可精确知道是哪条消息被确认或拒绝。

**作用 2：关联发送与 Return**。ReturnsCallback 中通过 `returned.getMessage().getMessageProperties().getCorrelationId()` 获取同一个 ID。

**作用 3：全链路追踪**。日志搜索 `file-upload:123` 可找到从 Producer 发送 → Broker 确认 → Consumer 处理 → DLQ（如果失败）的完整链路。

**唯一性保证**：`fileId` + 8 位 UUID 后缀。同一 fileId 在重新发送时 UUID 不同，correlationId 全局唯一。

### 6.7 消息持久化三层条件

| 层 | 实现方式 | 代码位置 | 重启后行为 | 如果缺失 |
|:--|:--|:--|:--|:--|
| **Exchange** | `new DirectExchange(name, true, false)` | `RabbitMqConfig.fileUploadExchange()` | 交换机元数据保留 | 生产者发送失败（Exchange 不存在），Confirm ack=false |
| **Queue** | `QueueBuilder.durable(name)` | `RabbitMqConfig.fileUploadQueue()` | 队列定义和消息保留 | 队列及消息全部丢失 |
| **Message** | `deliveryMode=PERSISTENT` | `FileUploadProducer.sendUploadTask()` | 消息从磁盘恢复 | 队列仍在但非持久化消息丢失（仅存内存） |

**三者缺一不可**。Exchange durable 保证路由元数据不丢，Queue durable 保证队列定义和存储不丢，Message PERSISTENT 保证消息体写入磁盘而非仅存内存。

```java
// Producer 中设置消息持久化
MessagePostProcessor postProcessor = msg -> {
    msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    return msg;
};
```

`MessagePostProcessor` 修改的是 AMQP envelope 属性，不影响 JSON 序列化的 message body。两者互不干扰。

### 6.8 convertAndSend 的 AmqpException 处理

```java
try {
    rabbitTemplate.convertAndSend(exchange, routingKey, message, postProcessor, correlationData);
} catch (AmqpException e) {
    log.error("消息发送异常：fileId={}, correlationId={}, exchange={}, routingKey={}",
              fileId, correlationId, exchange, routingKey, e);
    throw e;  // ★ 继续向上抛，不吞掉
}
```

**为什么向上抛**：

1. `AmqpException` 在 `convertAndSend` **同步阶段**抛出（网络不通 / Broker 不可达 / 连接未建立），此时消息确定未发出。
2. `FileController.upload()` 的 `catch (Exception e)` 返回 `Result.error("上传失败：" + e.getMessage())` → HTTP 500。
3. `ChunkUploadService.mergeChunks()` 的 `catch (Exception e)` 返回 `ChunkMergeResponse.fail("MQ 消息发送失败，请联系管理员手动触发解析")`，此时合并已完成但解析未触发，`.part` 文件保留可重新合并。

**不吞掉的原因**：吞掉异常意味着调用方以为消息已发出，实际上未发出，DB 中文件保持 status=0 但无人处理——比"返回错误让用户重试"更差。

### 6.9 解决了什么

- ✅ Producer 发送后通过 ConfirmCallback 知道 Broker 是否接收
- ✅ routingKey 配置错误通过 ReturnsCallback 可感知（不再静默丢消息）
- ✅ 消息在 Broker 重启后不丢失（三层持久化）
- ✅ 每条消息有唯一 correlationId（`file-upload:{fileId}:{uuid8}`），可全链路追踪
- ✅ 普通上传（`FileController`）和分片合并（`ChunkUploadService`）复用同一条 MQ 可靠链路

### 6.10 没有解决什么

- ❌ Confirm ack=false 或 Return 事件仅记录日志，不做自动重发——需人工介入或后续扩展消息发送记录表
- ❌ 没有消息发送记录表（`mq_sent_record`）——无法区分"从未发送"和"发送了但 ack=false"
- ❌ ConfirmCallback 是异步的——可能在 `FileController.upload()` 已返回 "文件已提交" 之后才回调 ack=false，用户无法感知
- ❌ 没有在 ConfirmCallback / ReturnsCallback 中写入 DB——避免引入不完整的补偿逻辑

---

## 7. 可靠性边界总结

### 7.1 已覆盖

| # | 能力 | 实现方式 | 闭环 |
|:--|:--|:--|:--:|
| 1 | Producer → Broker 可确认 | `publisher-confirm-type: correlated` + `ConfirmCallback` | 4 |
| 2 | 消息无法路由可感知 | `mandatory=true` + `ReturnsCallback` | 4 |
| 3 | Broker 重启消息不丢失 | durable exchange + durable queue + `deliveryMode=PERSISTENT` | 3 + 4 |
| 4 | Consumer 成功后才确认 | `acknowledge-mode: manual` + `channel.basicAck` | 2 |
| 5 | Consumer 临时故障自动重试 | Spring Retry（最多 3 次，2s→4s 指数退避） | 2 |
| 6 | 重试耗尽进入死信队列 | `deadLetterExchange` → DLQ → `DeadLetterConsumer` | 3 |
| 7 | 死信可记录日志和 DB 状态 | x-death header → `log.warn` + `updateStatus(3, PARSE_FAILED, 死因)` | 3 |
| 8 | 普通上传和分片合并复用同一条 MQ 可靠链路 | `FileController` 和 `ChunkUploadService` 均调用 `FileUploadProducer.sendUploadTask()` | 4 |

### 7.2 未覆盖

| # | 未覆盖项 | 影响 | 后续归属 |
|:--|:--|:--|:--|
| 1 | **消息发送任务表** | Confirm ack=false 或 AmqpException 后无法自动补发 | Phase 3 |
| 2 | **自动补偿重发** | 发送失败的消息靠人工排查后手动重发 | Phase 3 |
| 3 | **Consumer 幂等消费表** | 重复消费时 OSS 文件被覆盖写、ES 文档被覆盖（浪费资源但业务结果正确） | Phase 3 |
| 4 | **分布式事务** | MySQL 写入和 MQ 发送不是原子的 | 不做 |
| 5 | **MySQL ↔ ES 最终一致性** | Consumer 中手动双写 MySQL + ES，无事务保证 | Phase 3（Canal） |
| 6 | **主动告警** | DLQ 堆积、Consumer 异常率上升时无法主动通知 | Phase 3/4 |
| 7 | **死信自动重投** | 死信需人工排查根因后手动处理 | 不做 |

---

## 8. 面试表达版本

> "我项目里文件上传后不是同步处理的——用户传完文件，Controller 发一条消息到 RabbitMQ 就返回了，后台 Consumer 慢慢做 OSS 上传、文本提取、ES 索引这些事情。
>
> 第一版把这个链路跑通了，但消息可靠性基本为零：自动 ACK 意味着消费者拿到消息就删了，崩了文件就丢；发送端发了就不管了，Broker 收没收到不知道；失败的消息也没有兜底，直接就丢了。
>
> 所以我花了一周左右把 RabbitMQ 链路升级到生产级雏形，分三步做：
>
> 第一步，Consumer 改手动 ACK。只有 OSS 上传、文本提取、DB 更新、ES 索引全部完成后才确认消息。同时加了 Spring Retry，最多重试 3 次，2 秒 4 秒退避。不可重试的异常——比如文件被误删了——直接 Nack 掉，不浪费重试次数。
>
> 第二步，加死信队列。重试 3 次还失败的消息、或者被主动 Nack 的消息，不再丢弃，而是自动路由到死信队列。DeadLetterConsumer 从 x-death header 里提取原始队列和死因，记日志、写 DB，然后 ACK 掉。这样运维人员搜索 '【死信消息】' 就能找到所有失败记录。
>
> 第三步，改 Producer 端。不用默认交换机了，新建了一个业务交换机 `file.upload.exchange`，每条消息带 correlationId 发出去，格式是 `file-upload:{fileId}:{uuid8}`。加了 ConfirmCallback 确认 Broker 收到了没，加了 ReturnsCallback 在 mandatory=true 下捕获路由错误。同时把消息标记为持久化，配合 durable 的 exchange 和 queue，Broker 重启消息不丢。
>
> 边界也很清楚。现在没有消息发送记录表，Confirm 失败只是记日志，不自动重发。Consumer 也没有幂等消费表，重复消费目前是靠 OSS 覆盖写碰巧幂等。下一步计划引入 Canal 解决 MySQL 和 ES 的数据一致性问题，同时补上幂等消费和消息发送记录。"

---

## 9. 简历项目描述版本

以下 3 条按 STAR 风格，每条不超过 45 字：

**1. RabbitMQ 可靠性增强**

> 将 RabbitMQ 文件处理链路从 AUTO ACK 重构为 MANUAL ACK + Spring Retry(max 3) + DLX/DLQ 死信队列 + Producer Confirm + ReturnsCallback + 消息持久化，覆盖 7 种异常路径，消除静默丢消息。

**2. 大文件上传与 MQ 解析链路衔接**

> 实现 MD5 秒传 + 分片上传 + 流式合并全流程，合并完成后通过 Producer Confirm 可靠投递 MQ 触发异步解析（OSS + 文本提取 + ES 索引），合并失败保留 .part 文件支持断点续传。

**3. 故障可观测与失败兜底**

> 消息全链路携带 correlationId（file-upload:{id}:{uuid8}）实现端到端追踪；死信通过 x-death header 提取原始队列和死因写入 DB；Confirm / Return 事件由结构化日志暴露，支持 ELK 集中检索。

---

## 10. 后续阶段建议

### 当前项目状态

| 阶段 | 状态 |
|:--|:--|
| Phase 0 | ✅ 已完成（基础 CRUD + MQ + ES） |
| Phase 1 | ✅ 已完成（RabbitMQ 可靠性增强，3 个闭环） |
| Phase 2 | ✅ 已完成（大文件分片上传、MD5 秒传、断点续传、流式合并） |
| Phase 3 | 📋 计划中（Canal 数据一致性） |

### 推荐顺序与理由

| 优先级 | 方向 | 理由 |
|:--|:--|:--|
| **P0** | **Phase 3：Canal 数据一致性** | Phase 1 已保证 MQ 可靠，Phase 2 已保证上传链可靠，但 Consumer 中手动双写 MySQL + ES 仍是系统最薄弱的一环——无事务保证。Canal 监听 binlog 是最低成本的方案（不需改业务代码，仅新增 Canal Client + ES 同步服务）。面试中"MySQL ↔ ES 数据一致性"比"消息幂等"更有区分度。 |
| P1 | **消息幂等消费表** | 改动极小（一张 `mq_consumed_record` 表 + Mapper + Consumer 中 3 行 INSERT IGNORE），但能在面试中讲出"防重复消费"的完整方案。建议与 Canal 同期或 Canal 之后立即补齐。 |
| P2 | **消息发送记录表 / Outbox** | Phase 1 闭环 4 的 Confirm ack=false / Return 事件目前仅记日志。引入 `mq_sent_record` 表可以使"发送了并确认了"可查询。Outbox 模式能把 MySQL 写入和 MQ 发送绑定在同一事务中。建议在进入 RAG 之前完成。 |
| P3 | **Phase 5：RAG / 知识库问答** | RAG 是简历中最大亮点，但需要可靠的数据基础。Canal 保证 ES 数据一致性后，文本切片写入 `text_chunk` 表也能通过 binlog 自动同步到 ES，不再手动双写。 |

### 为什么不先做 RAG

RAG 是功能层面的大亮点，但如果底层数据不可靠——ES 和 MySQL 不一致、消息可能丢——RAG 的问答结果也会不可靠。工程上讲究"先稳定基础，再盖上层建筑"。Phase 1（MQ 可靠性）+ Phase 2（上传链路）+ Phase 3（数据一致性）构成了一个稳定的数据底座，之后再做 RAG（文本切片 + 向量检索 + LLM）才有可靠的输入。

---

## 11. 与现有文档的关系

| 文档 | 与本文档的关系 | 建议操作 |
|:--|:--|:--|
| `README.md` | 面向所有人的项目首页，Phase 1 仅 3 段概要（~15 行） | **保持现状**。README 继续承担"快速了解"的角色，本文档承担"深度复盘"的角色 |
| `docs/refactor-plan.md` | Phase 1 原始计划（包含 `task_retry_log`、`TaskCompensationService`、状态机校验等未实现内容） | **保留不动**，作为历史计划参考。本文档的 §2.2 已说明实际实现与计划的差异 |
| `docs/interview-notes.md` | 面试准备文档，RabbitMQ 部分仍是 Phase 0 状态（"目前版本用的是默认自动 ACK"） | **建议后续更新**：将第 3.1 节和第 4 节中关于 MQ 可靠性的问答替换为 Phase 1 完成后的表述 |
| `docs/architecture.md` | 系统架构说明，Consumer 数据流是 Phase 0 版本（无 MANUAL ACK 分叉、无 DLX/DLQ） | **建议后续更新**：Consumer 链路图增加 Manual ACK + DLX 分叉 |
| `docs/rabbitmq-reliability-review.md` | **本文档** — Phase 1 实际实现的完整工程复盘 | — |

---

> **文档结束** · 下一步：Phase 3 前置审计（Canal 引入对现有架构的影响评估）。
