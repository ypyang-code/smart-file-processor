package com.yang.fileprocessor.service;

import com.yang.fileprocessor.dto.*;
import com.yang.fileprocessor.entity.FileChunk;
import com.yang.fileprocessor.entity.FileChunkStatus;
import com.yang.fileprocessor.entity.FileInfo;
import com.yang.fileprocessor.enums.UploadStatusEnum;
import com.yang.fileprocessor.mapper.FileChunkMapper;
import com.yang.fileprocessor.utils.FileTypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 分片上传核心服务
 * <p>
 * 闭环 2：MD5 秒传检查
 * 闭环 3：分片初始化 + 单分片上传
 * 闭环 4：分片合并 + MD5 校验 + 触发异步解析
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Service
public class ChunkUploadService {

    private static final Logger log = LoggerFactory.getLogger(ChunkUploadService.class);

    /** 分片临时存储根目录 */
    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    @Autowired
    private FileInfoService fileInfoService;

    @Autowired
    private FileChunkMapper fileChunkMapper;

    @Autowired
    private FileUploadProducer fileUploadProducer;

    // ==================== 闭环 2：MD5 秒传 ====================

    /**
     * MD5 秒传检查（闭环 2）
     */
    public Md5CheckResponse md5Check(Md5CheckRequest request) {
        if (!StringUtils.hasText(request.getFileMd5())) {
            log.warn("MD5 秒传校验参数错误：fileMd5 为空");
            Md5CheckResponse resp = new Md5CheckResponse();
            resp.setInstantUpload(false);
            resp.setMessage("参数错误：fileMd5 不能为空");
            return resp;
        }
        if (request.getFileSize() == null || request.getFileSize() <= 0) {
            log.warn("MD5 秒传校验参数错误：fileSize={}", request.getFileSize());
            Md5CheckResponse resp = new Md5CheckResponse();
            resp.setInstantUpload(false);
            resp.setMessage("参数错误：fileSize 必须大于 0");
            return resp;
        }

        String normalizedMd5 = request.getFileMd5().toLowerCase().trim();
        log.info("MD5 秒传校验：fileMd5={}, fileSize={}, originalFilename={}",
                normalizedMd5, request.getFileSize(), request.getOriginalFilename());

        FileInfo existing = fileInfoService.findByMd5AndSize(normalizedMd5, request.getFileSize());
        if (existing != null) {
            log.info("MD5 秒传命中：fileId={}, fileName={}", existing.getId(), existing.getFileName());
            return Md5CheckResponse.hit(existing);
        }

        log.info("MD5 秒传未命中：fileMd5={}, fileSize={}", normalizedMd5, request.getFileSize());
        return Md5CheckResponse.miss();
    }

    // ==================== 闭环 3：分片初始化 + 单分片上传 ====================

    /**
     * 初始化分片上传任务
     * <p>
     * 1. 校验参数
     * 2. 检查是否已存在 UPLOADED 记录 → 返回秒传提示
     * 3. 检查是否已有进行中的分片任务 → 复用
     * 4. 新建 file_info 记录（upload_status=INIT）
     */
    public ChunkInitResponse initChunkUpload(ChunkInitRequest request) {
        // === 参数校验 ===
        if (!StringUtils.hasText(request.getFileMd5())) {
            return buildInitError("参数错误：fileMd5 不能为空");
        }
        if (request.getFileSize() == null || request.getFileSize() <= 0) {
            return buildInitError("参数错误：fileSize 必须大于 0");
        }
        if (request.getTotalChunks() == null || request.getTotalChunks() <= 0) {
            return buildInitError("参数错误：totalChunks 必须大于 0");
        }
        if (request.getChunkSize() == null || request.getChunkSize() <= 0) {
            return buildInitError("参数错误：chunkSize 必须大于 0");
        }
        if (!StringUtils.hasText(request.getOriginalFilename())) {
            return buildInitError("参数错误：originalFilename 不能为空");
        }

        String normalizedMd5 = request.getFileMd5().toLowerCase().trim();
        String originalFilename = request.getOriginalFilename();

        log.info("初始化分片上传：fileMd5={}, fileSize={}, totalChunks={}, chunkSize={}, name={}",
                normalizedMd5, request.getFileSize(), request.getTotalChunks(),
                request.getChunkSize(), originalFilename);

        // === 检查是否已上传完成 ===
        FileInfo completed = fileInfoService.findByMd5AndSize(normalizedMd5, request.getFileSize());
        if (completed != null) {
            log.info("文件已上传完成，无需分片上传：fileId={}", completed.getId());
            ChunkInitResponse resp = new ChunkInitResponse();
            resp.setFileId(completed.getId());
            resp.setFileMd5(normalizedMd5);
            resp.setUploadStatus(completed.getUploadStatus());
            resp.setUploadedChunks(completed.getTotalChunks());
            resp.setTotalChunks(completed.getTotalChunks());
            resp.setMessage("文件已存在且完整，可直接秒传，无需分片上传");
            return resp;
        }

        // === 检查是否有进行中的分片任务（复用） ===
        FileInfo inProgress = fileInfoService.findByMd5AnyStatus(normalizedMd5);
        if (inProgress != null) {
            log.info("复用已有分片上传任务：fileId={}, uploadStatus={}, uploadedChunks={}/{}",
                    inProgress.getId(), inProgress.getUploadStatus(),
                    inProgress.getUploadedChunks(), inProgress.getTotalChunks());

            ChunkInitResponse resp = new ChunkInitResponse();
            resp.setFileId(inProgress.getId());
            resp.setFileMd5(normalizedMd5);
            resp.setUploadStatus(inProgress.getUploadStatus());
            resp.setUploadedChunks(inProgress.getUploadedChunks() != null ? inProgress.getUploadedChunks() : 0);
            resp.setTotalChunks(inProgress.getTotalChunks());
            resp.setMessage("复用已有上传任务，已上传 " + resp.getUploadedChunks() + "/" + resp.getTotalChunks() + " 片");
            return resp;
        }

        // === 新建 file_info 记录 ===
        String fileType = FileTypeUtil.getFileType(originalFilename);
        FileInfo newFile = fileInfoService.saveChunkedFileInfo(
                originalFilename, fileType, request.getFileSize(),
                normalizedMd5, request.getTotalChunks());

        log.info("创建分片上传任务：fileId={}, fileMd5={}, totalChunks={}",
                newFile.getId(), normalizedMd5, request.getTotalChunks());

        ChunkInitResponse resp = new ChunkInitResponse();
        resp.setFileId(newFile.getId());
        resp.setFileMd5(normalizedMd5);
        resp.setUploadStatus(newFile.getUploadStatus());
        resp.setUploadedChunks(0);
        resp.setTotalChunks(request.getTotalChunks());
        resp.setMessage("分片上传任务已创建，共 " + request.getTotalChunks() + " 片，每片 " + request.getChunkSize() + " 字节");
        return resp;
    }

    /**
     * 上传单个分片
     * <p>
     * 1. 校验参数
     * 2. 确保分片目录存在
     * 3. 流式写入分片到本地磁盘（uploadDir/chunks/{fileMd5}/{chunkIndex}.part）
     * 4. 写入 file_chunk 记录（ON DUPLICATE KEY UPDATE）
     * 5. 从 file_chunk 表统计已上传分片数，更新 file_info
     */
    public ChunkUploadResponse uploadChunk(String fileMd5, int chunkIndex, int totalChunks,
                                           long chunkSize, MultipartFile chunk) {
        String normalizedMd5 = (fileMd5 != null) ? fileMd5.toLowerCase().trim() : "";

        // === 参数校验 ===
        if (!StringUtils.hasText(normalizedMd5)) {
            return buildUploadError(null, null, "参数错误：fileMd5 不能为空");
        }
        if (chunkIndex < 0) {
            return buildUploadError(normalizedMd5, null, "参数错误：chunkIndex 不能为负数");
        }
        if (totalChunks <= 0) {
            return buildUploadError(normalizedMd5, null, "参数错误：totalChunks 必须大于 0");
        }
        if (chunkIndex >= totalChunks) {
            return buildUploadError(normalizedMd5, null,
                    "参数错误：chunkIndex=" + chunkIndex + " 超出范围 [0, " + (totalChunks - 1) + "]");
        }
        if (chunk == null || chunk.isEmpty()) {
            return buildUploadError(normalizedMd5, chunkIndex, "参数错误：分片文件不能为空");
        }

        // === 查找或推断 fileId ===
        Long fileId = null;
        FileInfo fileInfo = fileInfoService.findByMd5AnyStatus(normalizedMd5);
        if (fileInfo != null) {
            fileId = fileInfo.getId();
        }

        // === 保存分片文件（流式，不占内存） ===
        String chunkDir = uploadDir + File.separator + "chunks" + File.separator + normalizedMd5;
        String chunkPath = chunkDir + File.separator + chunkIndex + ".part";

        try {
            Files.createDirectories(Paths.get(chunkDir));
            try (InputStream in = chunk.getInputStream();
                 OutputStream out = new FileOutputStream(chunkPath)) {
                byte[] buffer = new byte[8192];
                int len;
                long written = 0;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    written += len;
                }
                log.debug("分片写入完成：fileMd5={}, chunkIndex={}, path={}, bytes={}",
                        normalizedMd5, chunkIndex, chunkPath, written);
            }
        } catch (IOException e) {
            log.error("分片写入失败：fileMd5={}, chunkIndex={}, path={}", normalizedMd5, chunkIndex, chunkPath, e);
            return buildUploadError(normalizedMd5, chunkIndex, "分片保存失败：" + e.getMessage());
        }

        // === 写入 file_chunk 记录（防重复） ===
        FileChunk chunkRecord = new FileChunk();
        chunkRecord.setFileId(fileId);
        chunkRecord.setFileMd5(normalizedMd5);
        chunkRecord.setChunkIndex(chunkIndex);
        chunkRecord.setChunkSize(chunkSize);
        chunkRecord.setChunkPath(chunkPath);
        chunkRecord.setUploadStatus(FileChunkStatus.UPLOADED.getCode());
        fileChunkMapper.insertOrUpdate(chunkRecord);

        // === 统计已上传分片数（以 file_chunk 表为准，不信任累加） ===
        int uploadedCount = fileChunkMapper.countUploadedChunks(normalizedMd5);

        // === 更新 file_info 进度 ===
        if (fileId != null) {
            fileInfoService.updateChunkProgress(fileId, uploadedCount, totalChunks);
        }

        boolean allDone = (uploadedCount >= totalChunks);
        String status = allDone ? "READY_TO_MERGE" : "UPLOADING";

        log.info("分片上传完成：fileMd5={}, chunkIndex={}, uploaded={}/{}, status={}",
                normalizedMd5, chunkIndex, uploadedCount, totalChunks, status);

        ChunkUploadResponse resp = new ChunkUploadResponse();
        resp.setFileMd5(normalizedMd5);
        resp.setChunkIndex(chunkIndex);
        resp.setUploadedChunks(uploadedCount);
        resp.setTotalChunks(totalChunks);
        resp.setUploadStatus(status);
        resp.setMessage(allDone
                ? "全部 " + totalChunks + " 片已上传，等待合并"
                : "分片 " + chunkIndex + " 上传成功，进度 " + uploadedCount + "/" + totalChunks);
        return resp;
    }

    // ==================== 闭环 4：分片合并 ====================

    /**
     * 分片合并 + MD5 校验 + 触发异步解析
     * <p>
     * 完整流程：
     * <ol>
     *   <li>校验 file_info 存在且 upload_status = READY_TO_MERGE</li>
     *   <li>校验 file_chunk 全部分片完整（数量、索引连续、状态 UPLOADED、文件存在）</li>
     *   <li>更新 upload_status 为 MERGING</li>
     *   <li>流式合并所有 .part 文件为完整文件</li>
     *   <li>计算合并后文件的 MD5，与 file_info.file_md5 对比</li>
     *   <li>MD5 不匹配 → 删除合并文件，upload_status = MERGE_FAILED</li>
     *   <li>MD5 匹配 → updateMergeInfo + parse_status=WAITING_PARSE（事务）</li>
     *   <li>发送 FileUploadMessage 到 RabbitMQ，复用现有 Consumer 链路</li>
     *   <li>清理 .part 分片文件和分片目录</li>
     * </ol>
     *
     * @param fileMd5 文件 MD5（由 Controller 层已做规范化处理）
     * @return 合并结果
     */
    public ChunkMergeResponse mergeChunks(String fileMd5) {
        String normalizedMd5 = fileMd5.toLowerCase().trim();

        if (!StringUtils.hasText(normalizedMd5)) {
            return ChunkMergeResponse.fail("参数错误：fileMd5 不能为空");
        }

        log.info("开始分片合并：fileMd5={}", normalizedMd5);

        // === 1. 查找 file_info ===
        FileInfo fileInfo = fileInfoService.findByMd5AnyStatus(normalizedMd5);
        if (fileInfo == null) {
            log.warn("合并失败：文件不存在 fileMd5={}", normalizedMd5);
            return ChunkMergeResponse.fail("文件不存在：fileMd5=" + normalizedMd5);
        }

        // === 2. 校验上传状态 ===
        if (!UploadStatusEnum.READY_TO_MERGE.getCode().equals(fileInfo.getUploadStatus())) {
            log.warn("合并失败：状态不正确 fileMd5={}, uploadStatus={}", normalizedMd5, fileInfo.getUploadStatus());
            return ChunkMergeResponse.fail(
                    "当前状态不允许合并：uploadStatus=" + fileInfo.getUploadStatus() + "，期望 READY_TO_MERGE",
                    fileInfo);
        }

        int totalChunks = fileInfo.getTotalChunks();

        // === 3. 查询全部分片（按 chunk_index 排序） ===
        List<FileChunk> chunks = fileChunkMapper.findByFileMd5(normalizedMd5);

        // === 4. 校验分片数量 ===
        if (chunks.size() != totalChunks) {
            log.warn("合并失败：分片数量不完整 fileMd5={}, expected={}, actual={}",
                    normalizedMd5, totalChunks, chunks.size());
            return ChunkMergeResponse.fail(
                    "分片数量不完整：期望 " + totalChunks + " 片，实际已上传 " + chunks.size() + " 片",
                    fileInfo);
        }

        // === 5. 校验 chunkIndex 连续覆盖 0..totalChunks-1 ===
        for (int i = 0; i < totalChunks; i++) {
            if (chunks.get(i).getChunkIndex() != i) {
                log.warn("合并失败：分片索引不连续 fileMd5={}, expected={}, actual={}",
                        normalizedMd5, i, chunks.get(i).getChunkIndex());
                return ChunkMergeResponse.fail(
                        "分片索引不连续：缺少 chunkIndex=" + i,
                        fileInfo);
            }
        }

        // === 6. 校验每个分片状态 + 文件存在 ===
        for (FileChunk chunk : chunks) {
            if (!FileChunkStatus.UPLOADED.getCode().equals(chunk.getUploadStatus())) {
                log.warn("合并失败：分片状态异常 fileMd5={}, chunkIndex={}, status={}",
                        normalizedMd5, chunk.getChunkIndex(), chunk.getUploadStatus());
                return ChunkMergeResponse.fail(
                        "分片 " + chunk.getChunkIndex() + " 状态异常：" + chunk.getUploadStatus(),
                        fileInfo);
            }
            java.io.File partFile = new java.io.File(chunk.getChunkPath());
            if (!partFile.exists()) {
                log.warn("合并失败：分片文件缺失 fileMd5={}, chunkIndex={}, path={}",
                        normalizedMd5, chunk.getChunkIndex(), chunk.getChunkPath());
                return ChunkMergeResponse.fail(
                        "分片文件不存在：chunkIndex=" + chunk.getChunkIndex(),
                        fileInfo);
            }
        }

        // === 7. 更新状态为 MERGING ===
        fileInfoService.updateUploadStatus(fileInfo.getId(), UploadStatusEnum.MERGING);
        log.info("状态已更新为 MERGING：fileId={}", fileInfo.getId());

        // === 8. 流式合并分片 ===
        String mergedPath = uploadDir + java.io.File.separator + fileInfo.getId() + "_" + fileInfo.getFileName();
        try {
            // 确保上传目录存在
            Files.createDirectories(Paths.get(uploadDir));
            mergePartFiles(chunks, mergedPath);
            log.info("分片流式合并完成：fileId={}, targetPath={}, totalChunks={}",
                    fileInfo.getId(), mergedPath, totalChunks);
        } catch (IOException e) {
            log.error("分片合并 IO 异常：fileId={}, path={}", fileInfo.getId(), mergedPath, e);
            fileInfoService.updateUploadStatus(fileInfo.getId(), UploadStatusEnum.MERGE_FAILED);
            // 清理可能存在的半成品文件
            new java.io.File(mergedPath).delete();
            return ChunkMergeResponse.fail("分片合并 IO 异常：" + e.getMessage(), fileInfo);
        }

        // === 9. 计算合并后文件的 MD5 ===
        String actualMd5;
        try {
            actualMd5 = computeFileMd5(mergedPath);
            log.info("合并后文件 MD5 计算完成：fileId={}, expected={}, actual={}",
                    fileInfo.getId(), normalizedMd5, actualMd5);
        } catch (Exception e) {
            log.error("MD5 计算异常：fileId={}, path={}", fileInfo.getId(), mergedPath, e);
            fileInfoService.updateUploadStatus(fileInfo.getId(), UploadStatusEnum.MERGE_FAILED);
            new java.io.File(mergedPath).delete();
            return ChunkMergeResponse.fail("MD5 计算异常：" + e.getMessage(), fileInfo);
        }

        // === 10. MD5 校验 ===
        if (!normalizedMd5.equals(actualMd5)) {
            log.error("MD5 校验失败：fileId={}, expected={}, actual={}",
                    fileInfo.getId(), normalizedMd5, actualMd5);
            // 删除合并失败的文件
            java.io.File badFile = new java.io.File(mergedPath);
            if (badFile.exists()) {
                badFile.delete();
                log.info("已删除 MD5 校验失败的合并文件：{}", mergedPath);
            }
            // 更新状态为 MERGE_FAILED（保留 .part 文件用于排查或重新合并）
            fileInfoService.updateUploadStatus(fileInfo.getId(), UploadStatusEnum.MERGE_FAILED);
            return ChunkMergeResponse.fail(
                    "MD5 校验失败：期望 " + normalizedMd5 + "，实际 " + actualMd5,
                    fileInfo);
        }

        // === 11. MD5 校验成功：事务更新 file_info（upload_status=UPLOADED + parse_status=WAITING_PARSE） ===
        fileInfoService.completeMerge(fileInfo.getId(), mergedPath);
        log.info("合并信息已更新：fileId={}, storagePath={}, uploadStatus=UPLOADED, parseStatus=WAITING_PARSE",
                fileInfo.getId(), mergedPath);

        // === 12. 发送 RabbitMQ 消息，复用现有 Consumer 链路 ===
        try {
            FileUploadMessage message = new FileUploadMessage();
            message.setFileId(fileInfo.getId());
            message.setFilePath(mergedPath);
            message.setFileName(fileInfo.getFileName());
            fileUploadProducer.sendUploadTask(message);
            log.info("已发送 MQ 消息：fileId={}, fileName={}", fileInfo.getId(), fileInfo.getFileName());
        } catch (Exception e) {
            // MQ 发送失败时，文件已标记为 UPLOADED + WAITING_PARSE，可后续手动补发
            log.error("MQ 消息发送失败：fileId={}, 文件已合并成功但未进入异步解析，需手动补发", fileInfo.getId(), e);
            return ChunkMergeResponse.fail(
                    "分片合并成功但 MQ 消息发送失败，请联系管理员手动触发解析：fileId=" + fileInfo.getId(),
                    fileInfo);
        }

        // === 13. 清理 .part 分片文件 ===
        int deletedCount = 0;
        for (FileChunk chunk : chunks) {
            java.io.File partFile = new java.io.File(chunk.getChunkPath());
            if (partFile.exists() && partFile.delete()) {
                deletedCount++;
            }
        }
        // 尝试删除空的分片目录
        String chunkDir = uploadDir + java.io.File.separator + "chunks" + java.io.File.separator + normalizedMd5;
        new java.io.File(chunkDir).delete(); // 仅当目录为空时删除成功

        log.info("分片合并全流程完成：fileId={}, mergedPath={}, cleanedChunks={}/{}",
                fileInfo.getId(), mergedPath, deletedCount, chunks.size());

        return ChunkMergeResponse.success(fileInfo, mergedPath);
    }

    // ==================== 私有工具方法 ====================

    /**
     * 流式合并多个 .part 文件为一个完整文件
     * <p>
     * 按 chunks 列表顺序（已按 chunk_index 升序排列）依次读取每个分片，
     * 流式写入目标文件。8KB 缓冲区，大文件不会撑爆内存。
     */
    private void mergePartFiles(List<FileChunk> chunks, String targetPath) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(targetPath), 8192)) {
            byte[] buffer = new byte[8192];
            for (FileChunk chunk : chunks) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(chunk.getChunkPath()), 8192)) {
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }
            }
            out.flush();
        }
    }

    /**
     * 流式计算文件的 MD5 值
     * <p>
     * 使用 MessageDigest 流式更新，不将文件整体加载到内存。
     *
     * @return 32 位小写十六进制字符串
     */
    private String computeFileMd5(String filePath) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = new BufferedInputStream(new FileInputStream(filePath), 8192)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                md.update(buffer, 0, len);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(32);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private ChunkInitResponse buildInitError(String message) {
        log.warn("分片初始化参数错误：{}", message);
        ChunkInitResponse resp = new ChunkInitResponse();
        resp.setUploadStatus("INIT");
        resp.setMessage(message);
        return resp;
    }

    private ChunkUploadResponse buildUploadError(String fileMd5, Integer chunkIndex, String message) {
        log.warn("分片上传参数错误：fileMd5={}, chunkIndex={}, msg={}", fileMd5, chunkIndex, message);
        ChunkUploadResponse resp = new ChunkUploadResponse();
        resp.setFileMd5(fileMd5);
        resp.setChunkIndex(chunkIndex);
        resp.setUploadStatus("UPLOADING");
        resp.setMessage(message);
        return resp;
    }
}
