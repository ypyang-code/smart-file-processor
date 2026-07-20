package com.yang.fileprocessor.mapper;

import com.yang.fileprocessor.entity.FileChunk;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 分片记录 Mapper
 * <p>
 * 核心 SQL 采用 INSERT ... ON DUPLICATE KEY UPDATE，
 * 保证同一 (file_md5, chunk_index) 重复上传时不会报唯一键冲突。
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Mapper
public interface FileChunkMapper {

    /**
     * 插入或更新分片记录
     * <p>
     * 如果 (file_md5, chunk_index) 已存在 → 更新 chunk_size, chunk_path, upload_status
     * 如果不存在 → 插入新记录
     */
    @Insert("INSERT INTO file_chunk (file_id, file_md5, chunk_index, chunk_size, chunk_path, upload_status, created_at) " +
            "VALUES (#{fileId}, #{fileMd5}, #{chunkIndex}, #{chunkSize}, #{chunkPath}, #{uploadStatus}, NOW()) " +
            "ON DUPLICATE KEY UPDATE " +
            "file_id = VALUES(file_id), " +
            "chunk_size = VALUES(chunk_size), " +
            "chunk_path = VALUES(chunk_path), " +
            "upload_status = VALUES(upload_status), " +
            "updated_at = NOW()")
    int insertOrUpdate(FileChunk chunk);

    /**
     * 按 fileMd5 查询全部分片
     */
    @Select("SELECT * FROM file_chunk WHERE file_md5 = #{fileMd5} ORDER BY chunk_index")
    List<FileChunk> findByFileMd5(String fileMd5);

    /**
     * 查询某个文件已成功上传的分片索引列表（断点续传用）
     */
    @Select("SELECT chunk_index FROM file_chunk WHERE file_md5 = #{fileMd5} AND upload_status = 'UPLOADED' ORDER BY chunk_index")
    List<Integer> findUploadedChunkIndexes(String fileMd5);

    /**
     * 统计某个文件已成功上传的分片总数
     */
    @Select("SELECT COUNT(*) FROM file_chunk WHERE file_md5 = #{fileMd5} AND upload_status = 'UPLOADED'")
    int countUploadedChunks(String fileMd5);

    /**
     * 按 fileMd5 删除所有分片记录（清理用，闭环 4 使用）
     */
    @Delete("DELETE FROM file_chunk WHERE file_md5 = #{fileMd5}")
    int deleteByFileMd5(String fileMd5);
}
