package com.yang.fileprocessor.service;

import com.yang.fileprocessor.entity.FileInfo;
import com.yang.fileprocessor.mapper.FileInfoMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class FileInfoService {

    @Autowired
    private FileInfoMapper fileInfoMapper;

    public FileInfo saveFileInfo(String fileName, String fileType, Long fileSize) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName(fileName);
        fileInfo.setFileType(fileType);
        fileInfo.setFileSize(fileSize);
        fileInfo.setStatus(0);
        fileInfoMapper.insert(fileInfo);
        return fileInfo;
    }

    public FileInfo getById(Long id) {
        return fileInfoMapper.findById(id);
    }

    public List<FileInfo> getAll() {
        return fileInfoMapper.findAll();
    }

    public void updateStatus(Long id, Integer status, String content, String ossUrl) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(id);
        fileInfo.setStatus(status);
        fileInfo.setContent(content);
        fileInfo.setOssUrl(ossUrl);
        fileInfoMapper.update(fileInfo);
    }
}