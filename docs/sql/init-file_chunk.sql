-- ============================================================
-- Smart File Processor · file_chunk 表（完整建表）
-- 适用于: 新环境首次建库
-- 数据库: file_processor (MySQL 8.0+)
-- 字符集: utf8mb4
-- 说明:   chunkIndex 从 0 开始，与客户端约定一致
-- 创建时间: 2026-07-15
-- ============================================================

USE file_processor;

DROP TABLE IF EXISTS file_chunk;

CREATE TABLE file_chunk (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    file_id         BIGINT        DEFAULT NULL           COMMENT '关联 file_info.id（init 前可能为空）',
    file_md5        VARCHAR(32)   NOT NULL               COMMENT '完整文件 MD5（分片查询的主键维度）',
    chunk_index     INT           NOT NULL               COMMENT '分片索引，从 0 开始',
    chunk_size      BIGINT        DEFAULT NULL           COMMENT '当前分片的实际字节数',
    chunk_path      VARCHAR(500)  NOT NULL               COMMENT '分片本地存储路径',
    upload_status   VARCHAR(20)   NOT NULL DEFAULT 'UPLOADED' COMMENT '上传状态: UPLOADED / FAILED',
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 唯一约束：同一文件的同一分片只保留一条记录
    UNIQUE KEY uk_file_md5_chunk_index (file_md5, chunk_index),

    -- 索引
    INDEX idx_file_md5 (file_md5),
    INDEX idx_file_id (file_id)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件分片上传记录表';
