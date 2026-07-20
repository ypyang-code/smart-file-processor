-- ============================================================
-- Smart File Processor · file_info 表增量升级（v1 → v2）
--
-- 适用范围: 已有 file_processor 数据库，已存在 file_info 表（v1）
-- 最低版本: MySQL 8.0+
-- 创建时间: 2026-07-15
-- 最后修正: 2026-07-15 — 移除 ADD COLUMN IF NOT EXISTS（MySQL 8.0.46 不支持）
--
-- ⚠️ ⚠️ ⚠️ 执行前必读 ⚠️ ⚠️ ⚠️
--
-- 1. 本脚本使用普通 ADD COLUMN（不含 IF NOT EXISTS），不可重复执行。
--    重复执行会报 "Duplicate column name" 错误。
--
-- 2. 执行前请先检查目标数据库当前字段：
--       USE file_processor;
--       DESC file_info;
--
-- 3. 判断规则：
--    - 如果 DESC 结果只有 9 列（id, file_name, file_type, file_size,
--      oss_url, content, status, create_time, update_time），说明是 v1，
--      → 可以安全执行整个脚本。
--    - 如果 DESC 结果已经包含 file_md5 / upload_status 等 v2 字段，
--      说明已经升级过，→ 不要执行本脚本。
--    - 如果部分字段存在、部分不存在，请逐条检查后选择性执行。
--
-- 4. 当前项目数据库（file_processor）已经完成 v2 升级（2026-07-15）。
--    本文件仅作为新环境或未升级环境的迁移参考。
--
-- 5. 建议执行前备份：
--       mysqldump -u root -p file_processor file_info > file_info_backup.sql
--
-- 6. 首次部署的新环境请使用 init-file_info-v2.sql，不要使用本脚本。
-- ============================================================

USE file_processor;

-- 1. 文件 MD5（秒传/去重用）
ALTER TABLE file_info
    ADD COLUMN file_md5 VARCHAR(32) DEFAULT NULL COMMENT '文件MD5（32位十六进制）';

-- 2. 本地存储路径
ALTER TABLE file_info
    ADD COLUMN storage_path VARCHAR(500) DEFAULT NULL COMMENT '本地存储路径';

-- 3. 上传状态（替代原 status 的上传维度）
ALTER TABLE file_info
    ADD COLUMN upload_status VARCHAR(20) DEFAULT 'UPLOADED' COMMENT '上传状态: INIT/UPLOADING/MERGING/UPLOADED/MERGE_FAILED';

-- 4. 解析状态（替代原 status 的解析维度）
ALTER TABLE file_info
    ADD COLUMN parse_status VARCHAR(20) DEFAULT 'NOT_PARSED' COMMENT '解析状态: NOT_PARSED/WAITING_PARSE/PARSING/PARSE_SUCCESS/PARSE_FAILED';

-- 5-6. 分片统计
ALTER TABLE file_info
    ADD COLUMN total_chunks INT DEFAULT 1 COMMENT '总分片数',
    ADD COLUMN uploaded_chunks INT DEFAULT 0 COMMENT '已上传分片数';

-- 7. 是否分片上传
ALTER TABLE file_info
    ADD COLUMN is_chunked TINYINT(1) DEFAULT 0 COMMENT '是否分片上传: 0=否, 1=是';

-- 8. 合并时间
ALTER TABLE file_info
    ADD COLUMN merge_time DATETIME DEFAULT NULL COMMENT '分片合并完成时间';

-- ========== 索引（注意：以下语句默认注释，按需执行） ==========
-- MySQL 8.0 不支持 CREATE INDEX IF NOT EXISTS，重复执行会报错。
-- 执行前请先检查索引是否已存在：
--   SHOW INDEX FROM file_info WHERE Key_name IN ('idx_file_md5', 'idx_upload_status', 'idx_parse_status');
-- 如果已存在，跳过；如果不存在，取消注释后执行。
--
-- CREATE INDEX idx_file_md5       ON file_info(file_md5);
-- CREATE INDEX idx_upload_status ON file_info(upload_status);
-- CREATE INDEX idx_parse_status  ON file_info(parse_status);

-- ========== 验证 ==========
-- 执行后运行以下 SQL 确认字段：
-- DESC file_info;
-- 预期结果：应包含 file_md5, storage_path, upload_status, parse_status, total_chunks, uploaded_chunks, is_chunked, merge_time
-- 总字段数应为 17
