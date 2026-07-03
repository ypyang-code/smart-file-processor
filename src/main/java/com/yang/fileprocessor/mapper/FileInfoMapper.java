package com.yang.fileprocessor.mapper;

import com.yang.fileprocessor.entity.FileInfo;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface FileInfoMapper {

    @Insert("INSERT INTO file_info(file_name, file_type, file_size, status) " +
            "VALUES(#{fileName}, #{fileType}, #{fileSize}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FileInfo fileInfo);

    @Select("SELECT * FROM file_info WHERE id = #{id}")
    FileInfo findById(Long id);

    @Select("SELECT * FROM file_info ORDER BY create_time DESC")
    List<FileInfo> findAll();

    @Update("UPDATE file_info SET status = #{status}, content = #{content}, " +
            "oss_url = #{ossUrl}, update_time = NOW() WHERE id = #{id}")
    int update(FileInfo fileInfo);
}