package com.yang.fileprocessor.mapper;

import com.yang.fileprocessor.entity.FileInfo;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface FileInfoMapper {

    @Insert("INSERT INTO file_info(file_name, file_type, file_size, file_md5, " +
            "storage_path, status, upload_status, parse_status, " +
            "total_chunks, uploaded_chunks, is_chunked) " +
            "VALUES(#{fileName}, #{fileType}, #{fileSize}, #{fileMd5}, " +
            "#{storagePath}, #{status}, #{uploadStatus}, #{parseStatus}, " +
            "#{totalChunks}, #{uploadedChunks}, #{isChunked})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FileInfo fileInfo);

    @Select("SELECT * FROM file_info WHERE id = #{id}")
    FileInfo findById(Long id);

    @Select("SELECT * FROM file_info ORDER BY create_time DESC")
    List<FileInfo> findAll();

    /**
     * 原有更新方法（保持兼容，仅更新 status / content / oss_url）
     */
    @Update("UPDATE file_info SET status = #{status}, content = #{content}, " +
            "oss_url = #{ossUrl}, update_time = NOW() WHERE id = #{id}")
    int update(FileInfo fileInfo);

    // ========== Phase 2 新增方法 ==========

    /**
     * 按 MD5 查询已上传成功的文件
     * 注意：闭环 2 起新代码请使用 findByMd5AndSizeAndStatus()
     */
    @Select("SELECT * FROM file_info WHERE file_md5 = #{fileMd5} AND status = 2 LIMIT 1")
    FileInfo findByMd5(String fileMd5);

    /**
     * MD5 秒传精确查询：按 fileMd5 + fileSize + uploadStatus 匹配
     * <p>
     * 仅要求文件上传完成（upload_status='UPLOADED'），不要求解析完成。
     * 文件大小作为第二条件，防止不同文件 MD5 碰撞的极端情况。
     */
    @Select("SELECT * FROM file_info WHERE file_md5 = #{fileMd5} AND file_size = #{fileSize} AND upload_status = 'UPLOADED' LIMIT 1")
    FileInfo findByMd5AndSizeAndStatus(@Param("fileMd5") String fileMd5,
                                       @Param("fileSize") Long fileSize);

    /**
     * 更新上传状态（分片上传流程使用）
     */
    @Update("UPDATE file_info SET upload_status = #{uploadStatus}, update_time = NOW() WHERE id = #{id}")
    int updateUploadStatus(@Param("id") Long id, @Param("uploadStatus") String uploadStatus);

    /**
     * 按 MD5 查任意状态的文件记录（分片初始化复用用）
     */
    @Select("SELECT * FROM file_info WHERE file_md5 = #{fileMd5} LIMIT 1")
    FileInfo findByMd5AnyStatus(String fileMd5);

    /**
     * 更新已上传分片数和上传状态（分片上传进度同步用）
     */
    @Update("UPDATE file_info SET uploaded_chunks = #{uploadedChunks}, " +
            "upload_status = #{uploadStatus}, update_time = NOW() WHERE id = #{id}")
    int updateChunkProgress(@Param("id") Long id,
                            @Param("uploadedChunks") int uploadedChunks,
                            @Param("uploadStatus") String uploadStatus);

    /**
     * 更新解析状态（Consumer 处理完成后使用）
     */
    @Update("UPDATE file_info SET parse_status = #{parseStatus}, update_time = NOW() WHERE id = #{id}")
    int updateParseStatus(@Param("id") Long id, @Param("parseStatus") String parseStatus);

    /**
     * 分片合并完成后更新（闭环 4 使用）
     */
    @Update("UPDATE file_info SET storage_path = #{storagePath}, " +
            "upload_status = 'UPLOADED', uploaded_chunks = total_chunks, " +
            "merge_time = #{mergeTime}, update_time = NOW() WHERE id = #{id}")
    int updateMergeInfo(@Param("id") Long id,
                        @Param("storagePath") String storagePath,
                        @Param("mergeTime") String mergeTime);

    // ========== 分页查询（Phase 3 闭环 2） ==========

    /**
     * 分页查询文件列表（按创建时间倒序）
     *
     * @param offset 偏移量
     * @param size   每页条数
     * @return 当前页文件列表
     */
    @Select("SELECT * FROM file_info ORDER BY create_time DESC LIMIT #{offset}, #{size}")
    List<FileInfo> findPage(@Param("offset") int offset, @Param("size") int size);

    /**
     * 文件总数
     */
    @Select("SELECT COUNT(*) FROM file_info")
    long count();
}