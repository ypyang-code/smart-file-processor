package com.yang.fileprocessor.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.yang.fileprocessor.config.OssConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;

/**
 * 阿里云 OSS 对象存储服务（单例 OSS 客户端）
 * <p>
 * OSS 客户端在应用启动时初始化一次，所有上传请求复用同一个客户端实例，
 * 应用关闭时 shutdown。阿里云 OSS SDK 的 {@link OSS} 客户端是线程安全的。
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Service
public class OssService {

    private static final Logger log = LoggerFactory.getLogger(OssService.class);

    @Autowired
    private OssConfig ossConfig;

    private OSS ossClient;

    /**
     * 应用启动时初始化 OSS 客户端
     * <p>
     * 初始化失败（网络不通 / 密钥错误 / endpoint 不可达）会阻止应用启动，
     * 避免运行时才发现 OSS 不可用。
     */
    @PostConstruct
    public void init() {
        log.info("正在初始化 OSS 客户端: endpoint={}, bucket={}",
                ossConfig.getEndpoint(), ossConfig.getBucketName());
        try {
            ossClient = new OSSClientBuilder().build(
                    ossConfig.getEndpoint(),
                    ossConfig.getAccessKeyId(),
                    ossConfig.getAccessKeySecret()
            );
            log.info("OSS 客户端初始化成功");
        } catch (Exception e) {
            log.error("OSS 客户端初始化失败: endpoint={}, 原因: {}",
                    ossConfig.getEndpoint(), e.getMessage(), e);
            throw new RuntimeException("OSS 客户端初始化失败，请检查 aliyun.oss 配置: " + e.getMessage(), e);
        }
    }

    /**
     * 应用关闭时销毁 OSS 客户端
     */
    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            try {
                ossClient.shutdown();
                log.info("OSS 客户端已关闭");
            } catch (Exception e) {
                log.warn("OSS 客户端关闭时出现异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 上传文件到 OSS
     * <p>
     * 复用已初始化的单例客户端，不再每次创建/销毁。
     * 调用方负责关闭 {@code inputStream}。
     *
     * @param fileName    OSS 对象名（不含 bucket）
     * @param inputStream 文件输入流
     * @return 文件公网访问 URL
     */
    public String uploadFile(String fileName, InputStream inputStream) {
        ossClient.putObject(ossConfig.getBucketName(), fileName, inputStream);
        return "https://" + ossConfig.getBucketName() + "."
                + ossConfig.getEndpoint() + "/" + fileName;
    }
}
