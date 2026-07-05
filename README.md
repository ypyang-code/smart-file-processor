# Smart File Processor

> 基于 Spring Boot 的智能文件处理系统，支持文件上传、异步处理、云端存储、文本内容提取与全文检索。

Smart File Processor 是一个面向文档管理与内容检索场景的文件处理系统。项目采用“上传即返回、后台异步处理”的设计：用户上传文件后，系统先保存文件元数据并投递 RabbitMQ 消息，再由消费者异步完成 OSS 上传、文本解析、MySQL 状态更新和 Elasticsearch 索引构建，最终支持文件列表查看、内容查看和关键词全文搜索。

## 功能特性

- 文件上传：支持通过 Web 页面上传文件，单文件大小限制 50MB。
- 异步处理：上传接口不阻塞等待文件解析，使用 RabbitMQ 将耗时任务交给后台消费者处理。
- 云端存储：处理完成后将文件上传至阿里云 OSS，并保存可访问 URL。
- 内容提取：支持 PDF、Word `.docx`、TXT 文件文本内容提取。
- 文件管理：支持查询文件列表、查看单个文件处理状态、查看解析后的文本内容。
- 全文检索：将文件名和文本内容写入 Elasticsearch，支持关键词搜索。
- 前端页面：内置 Vue 3 + Element Plus 静态页面，提供上传、统计、搜索、列表和下载入口。

## 技术栈

- Java 17
- Spring Boot 3.2.8
- Gradle
- MyBatis
- MySQL
- RabbitMQ
- Elasticsearch
- 阿里云 OSS
- Apache PDFBox
- Apache POI
- Lombok
- Vue 3
- Element Plus

## 系统流程

```text
用户上传文件
    |
    v
FileController 保存文件元数据到 MySQL，状态为待处理
    |
    v
文件临时落盘，并发送 FileUploadMessage 到 RabbitMQ
    |
    v
FileUploadConsumer 异步消费消息
    |
    +--> 上传原文件到阿里云 OSS
    +--> 使用 PDFBox / POI / 文件流提取文本内容
    +--> 更新 MySQL 文件状态、内容、OSS 地址
    +--> 写入 Elasticsearch 索引 file_index
    +--> 删除本地临时文件
    |
    v
前端查询文件状态 / 查看内容 / 执行全文搜索
```

## 项目结构

```text
file-processor/
├── build.gradle
├── settings.gradle
├── src/
│   ├── main/
│   │   ├── java/com/yang/fileprocessor/
│   │   │   ├── FileProcessorApplication.java
│   │   │   ├── config/
│   │   │   │   ├── OssConfig.java
│   │   │   │   └── RabbitMqConfig.java
│   │   │   ├── controller/
│   │   │   │   ├── FileController.java
│   │   │   │   └── SearchController.java
│   │   │   ├── dto/
│   │   │   │   ├── FileUploadMessage.java
│   │   │   │   └── Result.java
│   │   │   ├── entity/
│   │   │   │   ├── FileDocument.java
│   │   │   │   └── FileInfo.java
│   │   │   ├── mapper/
│   │   │   │   └── FileInfoMapper.java
│   │   │   └── service/
│   │   │       ├── FileContentExtractor.java
│   │   │       ├── FileInfoService.java
│   │   │       ├── FileUploadConsumer.java
│   │   │       ├── FileUploadProducer.java
│   │   │       └── OssService.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── static/index.html
│   └── test/
└── uploads/
```

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/ypyang-code/smart-file-processor.git
cd smart-file-processor
```

### 2. 准备基础服务

请先确保本地或服务器已启动：

- MySQL
- RabbitMQ
- Elasticsearch
- 阿里云 OSS Bucket

### 3. 创建数据库表

```sql
CREATE DATABASE IF NOT EXISTS file_processor
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE file_processor;

CREATE TABLE IF NOT EXISTS file_info (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_name VARCHAR(255) NOT NULL,
  file_type VARCHAR(50),
  file_size BIGINT,
  oss_url VARCHAR(500),
  content LONGTEXT,
  status INT DEFAULT 0 COMMENT '0-待处理，2-处理完成，3-处理失败',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### 4. 修改配置

在 `src/main/resources/application.yml` 中配置数据库、RabbitMQ、Elasticsearch、OSS 和本地上传目录。

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/file_processor?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USERNAME:root}
    password: your_password
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
  elasticsearch:
    uris: http://localhost:9200

aliyun:
  oss:
    endpoint: oss-cn-hangzhou.aliyuncs.com
    access-key-id: your_access_key_id
    access-key-secret: your_access_key_secret
    bucket-name: your_bucket_name

file:
  upload-dir: uploads
```

> 建议不要将数据库密码、OSS AccessKey 等敏感信息提交到 GitHub，可改用环境变量或本地配置文件管理。

### 5. 启动项目

Windows：

```bash
gradlew.bat bootRun
```

macOS / Linux：

```bash
./gradlew bootRun
```

启动后访问：

```text
http://localhost:8080/index.html
```

## 接口说明

### 上传文件

```http
POST /api/file/upload
Content-Type: multipart/form-data
```

参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| file | MultipartFile | 上传的文件 |

返回示例：

```json
{
  "code": 200,
  "message": "文件已提交处理，fileId=1",
  "data": null
}
```

### 获取文件列表

```http
GET /api/file/list
```

### 获取单个文件详情

```http
GET /api/file/{id}
```

### 全文搜索

```http
GET /api/search?keyword=合同
```

搜索范围：

- 文件名
- 文件解析后的正文内容

## 文件状态

| 状态码 | 含义 |
| --- | --- |
| 0 | 待处理 |
| 2 | 处理完成 |
| 3 | 处理失败 |

## 核心实现亮点

- 使用 RabbitMQ 解耦上传接口和文件处理任务，避免大文件上传后长时间阻塞请求。
- 使用 OSS 保存原始文件，数据库仅保存文件元数据、处理状态和访问地址，降低本地存储压力。
- 使用 PDFBox 和 POI 对 PDF、Word 文档进行文本提取，为后续搜索和智能分析提供内容基础。
- 使用 Elasticsearch 建立文件名和正文内容索引，实现面向文档内容的关键词检索。
- 前后端接口采用统一 `Result` 响应结构，便于前端页面统一处理成功和失败状态。

## 可优化方向

- 将数据库密码、OSS AccessKey 等敏感配置迁移到环境变量或独立的本地配置文件。
- 为 RabbitMQ 增加重试机制、死信队列和失败告警，提升异步任务可靠性。
- 增加文件类型、文件大小、文件名安全校验，避免非法文件或路径穿越风险。
- 对 OSS 上传流使用 `try-with-resources`，进一步提升资源释放的稳定性。
- 为 Elasticsearch 索引增加初始化逻辑和中文分词配置，提升中文全文检索效果。
- 将文件处理状态扩展为“待处理、处理中、处理成功、处理失败”，让前端展示更准确。
- 增加接口测试和核心服务单元测试，覆盖上传成功、格式不支持、解析失败、搜索异常等场景。
- 增加 Docker Compose，一键启动 MySQL、RabbitMQ、Elasticsearch，降低项目运行门槛。
- 扩展 OCR 或大模型摘要能力，将项目升级为智能文档分析系统。
