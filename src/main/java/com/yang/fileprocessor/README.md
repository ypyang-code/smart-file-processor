# 智能文件处理系统

一个基于 Spring Boot 的企业级文件管理平台，支持文件上传、云存储、内容提取、全文检索等功能。

## 项目简介

智能文件处理系统是一个前后端分离的 Web 应用，旨在提供高效的文件管理和智能内容检索服务。系统采用异步消息队列处理文件上传任务，支持多种文件格式的内容提取，并基于 Elasticsearch 实现全文搜索。

## 技术架构

### 后端技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.8 | 核心框架 |
| MyBatis | 3.5.14 | ORM 框架 |
| MySQL | 8.3.0 | 关系型数据库 |
| RabbitMQ | 3.13 | 消息队列 |
| Elasticsearch | 8.12 | 搜索引擎 |
| 阿里云 OSS | 3.17.4 | 对象存储 |
| Apache PDFBox | 3.0.1 | PDF 内容提取 |
| Apache POI | 5.2.5 | Word 内容提取 |

### 前端技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.x | 前端框架 |
| Element Plus | 2.x | UI 组件库 |

## 核心功能

### 1. 文件上传
- 支持拖拽上传和点击上传
- 支持 PDF、Word、TXT、图片等多种格式
- 单文件最大 50MB
- 异步处理，上传后立即返回

### 2. 云存储
- 文件存储在阿里云 OSS
- 支持公网访问和下载
- 自动生成唯一文件名

### 3. 异步处理
- 基于 RabbitMQ 的消息队列
- 上传后立即返回，后台处理
- 支持失败重试机制

### 4. 内容提取
- 自动提取 PDF 文字内容
- 自动提取 Word(.docx) 文字内容
- 支持 TXT 文本读取
- 提取内容存入数据库和搜索引擎

### 5. 全文检索
- 基于 Elasticsearch 的关键词搜索
- 支持文件名和内容搜索
- 实时返回搜索结果

## 项目结构
file-processor/ ├── src/main/java/com/yang/fileprocessor/ │ 
├── config/ # 配置类 │ 
├── controller/ # 控制器层 │ 
├── service/ # 服务层 │ 
├── entity/ # 实体类 │ 
├── dto/ # 数据传输对象 │ 
└── mapper/ # MyBatis 映射器 
├── src/main/resources/ │ 
├── static/ # 静态资源 │ 
├── mapper/ # MyBatis XML │ 
└── application.yml # 配置文件 
└── build.gradle # Gradle 构建文件

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8.0+
- RabbitMQ 3.13+
- Elasticsearch 8.12+

### 1. 配置数据库

```sql
CREATE DATABASE file_processor CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

### 2. 配置 application.yml

修改数据库、RabbitMQ、Elasticsearch、OSS 配置。

### 3. 启动依赖服务

```bash
RabbitMQ
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management

Elasticsearch
docker run -d --name elasticsearch -p 9200:9200 -e "discovery.type=single-node" elasticsearch:8.12.0

### 4. 创建 ES 索引

```bash
curl -X PUT http://localhost:9200/file_index -H "Content-Type: application/json" -d '{ "mappings": { "properties": { "id": {"type": "long"}, "fileName": {"type": "text"}, "content": {"type": "text"}, "fileType": {"type": "keyword"}, "ossUrl": {"type": "keyword"} } } }'

### 5. 运行项目

```bash
./gradlew bootRun

### 6. 访问应用

http://localhost:8080/index.html

## API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/file/upload | POST | 文件上传 |
| /api/file/list | GET | 文件列表 |
| /api/file/{id} | GET | 文件详情 |
| /api/search?keyword=xxx | GET | 全文搜索 |

## 项目亮点

1. **异步架构**：RabbitMQ 解耦上传和处理
2. **云原生存储**：阿里云 OSS 海量文件存储
3. **智能提取**：自动提取 PDF/Word/TXT 内容
4. **全文搜索**：Elasticsearch 毫秒级检索
5. **前后端分离**：RESTful API + Vue 3

## 学习收获

- Spring Boot 企业级开发
- MyBatis 数据库操作
- RabbitMQ 消息队列
- Elasticsearch 全文检索
- 阿里云 OSS 对象存储
- PDF/Word 内容提取
- Vue 3 + Element Plus 前端开发

## 后续优化

1. 用户认证和权限管理
2. 文件分类和标签系统
3. 批量上传和下载
4. 文件在线预览
5. 数据可视化统计

---

**作者**：杨昀璞  
**日期**：2026-06-13