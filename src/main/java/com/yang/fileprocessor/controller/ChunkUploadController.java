package com.yang.fileprocessor.controller;

import com.yang.fileprocessor.dto.*;
import com.yang.fileprocessor.service.ChunkUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 分片上传控制器
 * <p>
 * 提供大文件分片上传相关接口：
 * - MD5 秒传检查（闭环 2 ✅）
 * - 分片初始化（闭环 3 ✅）
 * - 分片上传（闭环 3 ✅）
 * - 分片合并（闭环 4 ✅）
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@RestController
@RequestMapping("/api/file/chunk")
public class ChunkUploadController {

    @Autowired
    private ChunkUploadService chunkUploadService;

    /**
     * MD5 秒传检查（闭环 2）
     */
    @PostMapping("/check")
    public Result<Md5CheckResponse> check(@RequestBody Md5CheckRequest request) {
        Md5CheckResponse response = chunkUploadService.md5Check(request);
        return Result.success(response);
    }

    /**
     * 初始化分片上传任务（闭环 3）
     * <p>
     * 客户端在确认文件需要分片上传后调用，创建或复用上传任务。
     *
     * <pre>
     * Request:  POST /api/file/chunk/init
     * Body:     { "fileMd5": "...", "fileSize": 524288000, "originalFilename": "大型文档.pdf",
     *             "totalChunks": 100, "chunkSize": 5242880 }
     * Response: { "code": 200, "data": { "fileId": 456, "fileMd5": "...",
     *             "uploadStatus": "UPLOADING", "uploadedChunks": 0, "totalChunks": 100, ... } }
     * </pre>
     */
    @PostMapping("/init")
    public Result<ChunkInitResponse> init(@RequestBody ChunkInitRequest request) {
        ChunkInitResponse response = chunkUploadService.initChunkUpload(request);
        return Result.success(response);
    }

    /**
     * 上传单个分片（闭环 3）
     * <p>
     * 客户端按顺序或并发上传每个分片。分片索引从 0 开始。
     * 重复上传同一分片不会报错（ON DUPLICATE KEY UPDATE）。
     *
     * <pre>
     * Request:  POST /api/file/chunk/upload
     * Content-Type: multipart/form-data
     * Fields:   chunk (file), fileMd5, chunkIndex, totalChunks, chunkSize
     * Response: { "code": 200, "data": { "fileMd5": "...", "chunkIndex": 3,
     *             "uploadedChunks": 37, "totalChunks": 100, "uploadStatus": "UPLOADING", ... } }
     * </pre>
     */
    @PostMapping("/upload")
    public Result<ChunkUploadResponse> upload(
            @RequestParam("chunk") MultipartFile chunk,
            @RequestParam("fileMd5") String fileMd5,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("chunkSize") long chunkSize) {

        ChunkUploadResponse response = chunkUploadService.uploadChunk(
                fileMd5, chunkIndex, totalChunks, chunkSize, chunk);
        return Result.success(response);
    }

    /**
     * 分片合并 + MD5 校验 + 触发异步解析（闭环 4）
     * <p>
     * 客户端在所有分片上传完成后调用此接口。服务端将：
     * <ol>
     *   <li>校验文件状态为 READY_TO_MERGE</li>
     *   <li>校验全部分片完整且连续</li>
     *   <li>流式合并 .part 文件为完整文件</li>
     *   <li>计算合并后文件的 MD5 并与客户端上报值对比</li>
     *   <li>MD5 一致 → 更新状态为 UPLOADED，发送 MQ 进入异步解析</li>
     *   <li>MD5 不一致 → 标记 MERGE_FAILED，保留分片文件</li>
     * </ol>
     *
     * <pre>
     * Request:  POST /api/file/chunk/merge
     * Body:     { "fileMd5": "d41d8cd98f00b204e9800998ecf8427e" }
     * Response: { "code": 200, "data": { "success": true, "fileId": 1,
     *             "uploadStatus": "UPLOADED", "parseStatus": "WAITING_PARSE", ... } }
     * </pre>
     */
    @PostMapping("/merge")
    public Result<ChunkMergeResponse> merge(@RequestBody ChunkMergeRequest request) {
        if (request.getFileMd5() == null || request.getFileMd5().isBlank()) {
            return Result.success(ChunkMergeResponse.fail("参数错误：fileMd5 不能为空"));
        }
        ChunkMergeResponse response = chunkUploadService.mergeChunks(request.getFileMd5());
        return Result.success(response);
    }
}
