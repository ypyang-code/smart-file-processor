-- ============================================================
-- Smart File Processor · file_info 表（v2 完整建表）
-- 适用于: 新环境首次建库
-- 数据库: file_processor (MySQL 8.0+)
-- 字符集: utf8mb4
-- 创建时间: 2026-07-15
-- ============================================================

CREATE DATABASE IF NOT EXISTS file_processor
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE file_processor;

DROP TABLE IF EXISTS file_info;

CREATE TABLE file_info (
    -- ========== 基础字段 ==========
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    file_name       VARCHAR(255)  NOT NULL              COMMENT '原始文件名',
    file_type       VARCHAR(20)   NOT NULL              COMMENT '文件类型: pdf / word / text / image / other',
    file_size       BIGINT        NOT NULL              COMMENT '文件字节数',

    -- ========== Phase 2 新增：MD5 与存储 ==========
    file_md5        VARCHAR(32)   DEFAULT NULL          COMMENT '文件 MD5（32 位十六进制），秒传/去重/完整性校验',
    storage_path    VARCHAR(500)  DEFAULT NULL          COMMENT '本地存储路径（简单上传=完整文件，分片上传=合并后文件）',

    -- ========== 原有字段 ==========
    status          INT           DEFAULT 0             COMMENT '旧状态字段（保留兼容）：0=待处理, 1=处理中, 2=已完成, 3=失败',
    oss_url         VARCHAR(500)  DEFAULT NULL          COMMENT '阿里云 OSS 公网访问 URL',
    content         MEDIUMTEXT    DEFAULT NULL          COMMENT '提取的文本内容',

    -- ========== Phase 2 新增：上传状态 ==========
    upload_status   VARCHAR(20)   DEFAULT 'UPLOADED'    COMMENT '上传状态: INIT / UPLOADING / MERGING / UPLOADED / MERGE_FAILED',
    parse_status    VARCHAR(20)   DEFAULT 'NOT_PARSED'  COMMENT '解析状态: NOT_PARSED / WAITING_PARSE / PARSING / PARSE_SUCCESS / PARSE_FAILED',

    -- ========== Phase 2 新增：分片统计 ==========
    total_chunks    INT           DEFAULT 1             COMMENT '总分片数（非分片上传 = 1）',
    uploaded_chunks INT           DEFAULT 0             COMMENT '已上传分片数（非分片上传 = 0）',
    is_chunked      TINYINT(1)    DEFAULT 0             COMMENT '是否分片上传: 0=否, 1=是',
    merge_time      DATETIME      DEFAULT NULL          COMMENT '分片合并完成时间',

    -- ========== 时间戳 ==========
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- ========== 索引 ==========
    INDEX idx_file_md5 (file_md5),
    INDEX idx_status (status),
    INDEX idx_upload_status (upload_status),
    INDEX idx_parse_status (parse_status)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件信息表 v2';
