package com.yang.fileprocessor.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.yang.fileprocessor.config.OssConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.InputStream;

@Service
public class OssService {

    @Autowired
    private OssConfig ossConfig;

    /**
     * 上传文件到 OSS
     */
    public String uploadFile(String fileName, InputStream inputStream) {
        // 创建 OSS 客户端
        OSS ossClient = new OSSClientBuilder().build(
                ossConfig.getEndpoint(),
                ossConfig.getAccessKeyId(),
                ossConfig.getAccessKeySecret()
        );

        try {
            // 上传文件
            ossClient.putObject(ossConfig.getBucketName(), fileName, inputStream);

            // 返回文件访问 URL
            return "https://" + ossConfig.getBucketName() + "."
                    + ossConfig.getEndpoint() + "/" + fileName;

        } finally {
            // 关闭客户端
            ossClient.shutdown();
        }
    }
}