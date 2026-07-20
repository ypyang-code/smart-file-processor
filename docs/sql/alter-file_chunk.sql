-- ============================================================
-- Smart File Processor · file_chunk 表增量建表
--
-- 适用范围: 已有 file_processor 数据库，需要新增 file_chunk 表
-- 最低版本: MySQL 8.0+
-- 执行前: 确认当前数据库为 file_processor
-- 执行后: 验证  DESC file_chunk;  确认表存在
--
-- 注意: 首次部署的新环境请使用 init-file_chunk.sql，不要使用本脚本
-- 创建时间: 2026-07-15
-- ============================================================

USE file_processor;

-- file_chunk 表（如已存在则跳过）
CREATE TABLE IF NOT EXISTS file_chunk (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    file_id         BIGINT        DEFAULT NULL           COMMENT '关联 file_info.id',
    file_md5        VARCHAR(32)   NOT NULL               COMMENT '完整文件 MD5',
    chunk_index     INT           NOT NULL               COMMENT '分片索引，从 0 开始',
    chunk_size      BIGINT        DEFAULT NULL           COMMENT '当前分片实际字节数',
    chunk_path      VARCHAR(500)  NOT NULL               COMMENT '分片本地存储路径',
    upload_status   VARCHAR(20)   NOT NULL DEFAULT 'UPLOADED' COMMENT '上传状态: UPLOADED / FAILED',
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_file_md5_chunk_index (file_md5, chunk_index),
    INDEX idx_file_md5 (file_md5),
    INDEX idx_file_id (file_id)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件分片上传记录表';

-- ========== 验证 ==========
-- DESC file_chunk;
-- 预期结果：应包含 id, file_id, file_md5, chunk_index, chunk_size, chunk_path, upload_status, created_at, updated_at
