package com.yang.fileprocessor.service;

import com.yang.fileprocessor.dto.PageResult;
import com.yang.fileprocessor.entity.FileInfo;
import com.yang.fileprocessor.enums.ParseStatusEnum;
import com.yang.fileprocessor.enums.UploadStatusEnum;
import com.yang.fileprocessor.mapper.FileInfoMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class FileInfoService {

    @Autowired
    private FileInfoMapper fileInfoMapper;

    @Autowired
    private OutboxEventService outboxEventService;

    /**
     * 保存文件信息（简单上传）
     * <p>
     * 默认值：
     * - uploadStatus = UPLOADED（简单上传文件已就绪）
     * - parseStatus = NOT_PARSED（等待 MQ 消费者处理）
     * - isChunked = false, totalChunks = 1, uploadedChunks = 1
     */
    public FileInfo saveFileInfo(String fileName, String fileType, Long fileSize) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName(fileName);
        fileInfo.setFileType(fileType);
        fileInfo.setFileSize(fileSize);
        // 原有字段
        fileInfo.setStatus(0);
        // Phase 2 新增字段默认值
        fileInfo.setUploadStatus(UploadStatusEnum.UPLOADED.getCode());
        fileInfo.setParseStatus(ParseStatusEnum.NOT_PARSED.getCode());
        fileInfo.setIsChunked(false);
        fileInfo.setTotalChunks(1);
        fileInfo.setUploadedChunks(1);

        fileInfoMapper.insert(fileInfo);
        return fileInfo;
    }

    public FileInfo getById(Long id) {
        return fileInfoMapper.findById(id);
    }

    public List<FileInfo> getAll() {
        return fileInfoMapper.findAll();
    }

    /**
     * 更新状态（原有方法，保持兼容）
     * <p>
     * 同时根据 status 值自动设置 parseStatus：
     * - status=2 → parseStatus=PARSE_SUCCESS
     * - status=3 → parseStatus=PARSE_FAILED
     * <p>
     * 整个方法在一个事务中执行，保证 status/content/ossUrl 与 parseStatus 的原子写入。
     */
    @Transactional
    public void updateStatus(Long id, Integer status, String content, String ossUrl) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(id);
        fileInfo.setStatus(status);
        fileInfo.setContent(content);
        fileInfo.setOssUrl(ossUrl);
        fileInfoMapper.update(fileInfo);

        // 同步更新解析状态
        if (status != null && status == 2) {
            fileInfoMapper.updateParseStatus(id, ParseStatusEnum.PARSE_SUCCESS.getCode());
        } else if (status != null && status == 3) {
            fileInfoMapper.updateParseStatus(id, ParseStatusEnum.PARSE_FAILED.getCode());
        }
    }

    /**
     * 更新文件状态并写入 outbox 事件（同一事务）
     * <p>
     * 与 {@link #updateStatus(Long, Integer, String, String)} 的业务逻辑相同
     * （update file_info + 同步 parseStatus），额外在同事务内写入 outbox_event。
     * <p>
     * ES 同步不再在此方法内完成，而是由 {@link OutboxSyncScheduler} 异步处理。
     * 事务成功 = MySQL 已提交 + outbox 事件已写入 → Consumer 可以 ACK。
     *
     * @param id      文件 ID
     * @param status  状态值（2=成功, 3=失败）
     * @param content 提取的文本内容
     * @param ossUrl  OSS 访问 URL
     */
    @Transactional
    public void updateStatusWithOutbox(Long id, Integer status, String content, String ossUrl) {
        // 1. 更新 file_info（与 updateStatus 相同逻辑）
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(id);
        fileInfo.setStatus(status);
        fileInfo.setContent(content);
        fileInfo.setOssUrl(ossUrl);
        fileInfoMapper.update(fileInfo);

        // 同步更新解析状态
        if (status != null && status == 2) {
            fileInfoMapper.updateParseStatus(id, ParseStatusEnum.PARSE_SUCCESS.getCode());
        } else if (status != null && status == 3) {
            fileInfoMapper.updateParseStatus(id, ParseStatusEnum.PARSE_FAILED.getCode());
        }

        // 2. 同事务写入 outbox 事件（仅 status=2 成功时才需要同步 ES）
        if (status != null && status == 2) {
            String traceId = UUID.randomUUID().toString().replace("-", "");
            outboxEventService.createFileIndexEvent(id, traceId);
        }
    }

    // ========== Phase 2 新增方法 ==========

    /**
     * 按 MD5 查询已上传成功的文件（仅检查 fileMd5 + status=2）
     * 注意：闭环 2 起新代码请使用 findByMd5AndSize()
     */
    public FileInfo findByMd5(String fileMd5) {
        return fileInfoMapper.findByMd5(fileMd5);
    }

    /**
     * MD5 秒传精确查询（闭环 2 使用）
     * <p>
     * 按 fileMd5 + fileSize + uploadStatus='UPLOADED' 查询。
     * 仅要求文件上传完成，不要求解析完成（parse_status 任意值均可）。
     */
    public FileInfo findByMd5AndSize(String fileMd5, Long fileSize) {
        return fileInfoMapper.findByMd5AndSizeAndStatus(fileMd5, fileSize);
    }

    /**
     * 按 MD5 查任意状态的文件记录（闭环 3 分片初始化复用用）
     * <p>
     * 无论上传是否完成，只要 fileMd5 匹配就返回。
     * 用于分片初始化时判断是新建任务还是复用已有任务。
     */
    public FileInfo findByMd5AnyStatus(String fileMd5) {
        return fileInfoMapper.findByMd5AnyStatus(fileMd5);
    }

    /**
     * 保存分片上传的文件信息（闭环 3 使用）
     * <p>
     * 与 saveFileInfo() 的区别：
     * - upload_status = UPLOADING（而非 UPLOADED）
     * - is_chunked = true
     * - total_chunks 来自请求参数
     * - uploaded_chunks = 0
     * - fileMd5, fileType 从请求参数写入
     */
    public FileInfo saveChunkedFileInfo(String fileName, String fileType, Long fileSize,
                                        String fileMd5, int totalChunks) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName(fileName);
        fileInfo.setFileType(fileType);
        fileInfo.setFileSize(fileSize);
        fileInfo.setFileMd5(fileMd5);
        fileInfo.setStatus(0);
        fileInfo.setUploadStatus(UploadStatusEnum.INIT.getCode());
        fileInfo.setParseStatus(ParseStatusEnum.NOT_PARSED.getCode());
        fileInfo.setIsChunked(true);
        fileInfo.setTotalChunks(totalChunks);
        fileInfo.setUploadedChunks(0);

        fileInfoMapper.insert(fileInfo);
        return fileInfo;
    }

    /**
     * 更新上传状态（分片上传流程使用，闭环 2/3 使用）
     */
    public void updateUploadStatus(Long id, UploadStatusEnum status) {
        fileInfoMapper.updateUploadStatus(id, status.getCode());
    }

    /**
     * 更新分片上传进度（闭环 3 使用）
     * <p>
     * 同时更新 uploaded_chunks 和 upload_status。
     * 如果 uploadedChunks == totalChunks，状态自动设为 READY_TO_MERGE。
     */
    public void updateChunkProgress(Long id, int uploadedChunks, int totalChunks) {
        String newStatus = (uploadedChunks >= totalChunks)
                ? UploadStatusEnum.READY_TO_MERGE.getCode()
                : UploadStatusEnum.UPLOADING.getCode();
        fileInfoMapper.updateChunkProgress(id, uploadedChunks, newStatus);
    }

    /**
     * 更新解析状态（Consumer 或其他解析流程使用）
     */
    public void updateParseStatus(Long id, ParseStatusEnum status) {
        fileInfoMapper.updateParseStatus(id, status.getCode());
    }

    /**
     * 合并完成后更新文件信息（闭环 4 使用）
     * <p>
     * 在一个事务中原子完成：
     * - upload_status → UPLOADED
     * - uploaded_chunks = total_chunks
     * - storage_path 指向合并后的完整文件
     * - merge_time 记录合并完成时间
     * - parse_status → WAITING_PARSE（即将发送 MQ 消息）
     */
    @Transactional
    public void completeMerge(Long id, String storagePath) {
        String mergeTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        fileInfoMapper.updateMergeInfo(id, storagePath, mergeTime);
        fileInfoMapper.updateParseStatus(id, ParseStatusEnum.WAITING_PARSE.getCode());
    }

    // ========== 分页查询（Phase 3 闭环 2） ==========

    /**
     * 分页查询文件列表
     *
     * @param page 当前页码（从 1 开始）
     * @param size 每页条数
     * @return 分页结果
     */
    public PageResult<FileInfo> getPage(int page, int size) {
        long total = fileInfoMapper.count();
        int offset = (page - 1) * size;
        List<FileInfo> list = fileInfoMapper.findPage(offset, size);
        int totalPages = (int) Math.ceil((double) total / size);

        PageResult<FileInfo> result = new PageResult<>();
        result.setList(list);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setTotalPages(totalPages);
        return result;
    }
}