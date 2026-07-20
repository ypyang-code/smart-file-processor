package com.yang.fileprocessor.service;

import com.yang.fileprocessor.dto.*;
import com.yang.fileprocessor.entity.FileChunk;
import com.yang.fileprocessor.entity.FileChunkStatus;
import com.yang.fileprocessor.entity.FileInfo;
import com.yang.fileprocessor.mapper.FileChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChunkUploadServiceTest {

    @Mock
    private FileInfoService fileInfoService;

    @Mock
    private FileChunkMapper fileChunkMapper;

    @Mock
    private FileUploadProducer fileUploadProducer;

    @InjectMocks
    private ChunkUploadService chunkUploadService;

    @TempDir
    Path tempDir;

    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(chunkUploadService, "uploadDir", tempDir.toString());
    }

    // ==================== md5Check ====================

    @Test
    void md5Check_新文件_应返回未命中() {
        Md5CheckRequest request = new Md5CheckRequest();
        request.setFileMd5(MD5);
        request.setFileSize(1000L);
        request.setOriginalFilename("test.pdf");

        when(fileInfoService.findByMd5AndSize(MD5, 1000L)).thenReturn(null);

        Md5CheckResponse response = chunkUploadService.md5Check(request);

        assertThat(response.getInstantUpload()).isFalse();
        assertThat(response.getMessage()).contains("不存在");
    }

    @Test
    void md5Check_已存在文件_应返回秒传命中() {
        Md5CheckRequest request = new Md5CheckRequest();
        request.setFileMd5(MD5);
        request.setFileSize(1000L);

        FileInfo existing = new FileInfo();
        existing.setId(42L);
        existing.setFileName("existing.pdf");
        existing.setFileSize(1000L);
        existing.setUploadStatus("UPLOADED");
        existing.setParseStatus("PARSE_SUCCESS");
        existing.setOssUrl("https://bucket.oss.com/files/42_existing.pdf");

        when(fileInfoService.findByMd5AndSize(MD5, 1000L)).thenReturn(existing);

        Md5CheckResponse response = chunkUploadService.md5Check(request);

        assertThat(response.getInstantUpload()).isTrue();
        assertThat(response.getFileId()).isEqualTo(42L);
        assertThat(response.getFileName()).isEqualTo("existing.pdf");
        assertThat(response.getMessage()).contains("已存在");
    }

    @Test
    void md5Check_参数为空_应返回参数错误() {
        Md5CheckRequest request = new Md5CheckRequest();
        request.setFileMd5("");
        request.setFileSize(1000L);

        Md5CheckResponse response = chunkUploadService.md5Check(request);

        assertThat(response.getInstantUpload()).isFalse();
        assertThat(response.getMessage()).contains("参数错误");
    }

    @Test
    void md5Check_fileSize非法_应返回参数错误() {
        Md5CheckRequest request = new Md5CheckRequest();
        request.setFileMd5(MD5);
        request.setFileSize(null);

        Md5CheckResponse response = chunkUploadService.md5Check(request);

        assertThat(response.getInstantUpload()).isFalse();
        assertThat(response.getMessage()).contains("参数错误");
    }

    // ==================== initChunkUpload ====================

    @Test
    void initChunkUpload_正常初始化_应创建新任务() {
        ChunkInitRequest request = new ChunkInitRequest();
        request.setFileMd5(MD5);
        request.setFileSize(10_000_000L);
        request.setOriginalFilename("large.pdf");
        request.setTotalChunks(10);
        request.setChunkSize(1_000_000L);

        when(fileInfoService.findByMd5AndSize(MD5, 10_000_000L)).thenReturn(null);
        when(fileInfoService.findByMd5AnyStatus(MD5)).thenReturn(null);

        FileInfo newFile = new FileInfo();
        newFile.setId(100L);
        newFile.setFileMd5(MD5);
        newFile.setUploadStatus("INIT");
        newFile.setTotalChunks(10);
        when(fileInfoService.saveChunkedFileInfo(
                eq("large.pdf"), eq("pdf"), eq(10_000_000L), eq(MD5), eq(10)))
                .thenReturn(newFile);

        ChunkInitResponse response = chunkUploadService.initChunkUpload(request);

        assertThat(response.getFileId()).isEqualTo(100L);
        assertThat(response.getFileMd5()).isEqualTo(MD5);
        assertThat(response.getUploadedChunks()).isZero();
        assertThat(response.getTotalChunks()).isEqualTo(10);
        assertThat(response.getUploadStatus()).isEqualTo("INIT");
    }

    @Test
    void initChunkUpload_存在进行中任务_应复用() {
        ChunkInitRequest request = new ChunkInitRequest();
        request.setFileMd5(MD5);
        request.setFileSize(10_000_000L);
        request.setOriginalFilename("large.pdf");
        request.setTotalChunks(10);
        request.setChunkSize(1_000_000L);

        FileInfo completed = new FileInfo();
        completed.setId(99L);
        completed.setUploadStatus("UPLOADED");
        when(fileInfoService.findByMd5AndSize(MD5, 10_000_000L)).thenReturn(completed);

        ChunkInitResponse response = chunkUploadService.initChunkUpload(request);

        assertThat(response.getFileId()).isEqualTo(99L);
        assertThat(response.getMessage()).contains("已存在");
    }

    @Test
    void initChunkUpload_参数为空_应返回错误() {
        ChunkInitRequest request = new ChunkInitRequest();
        request.setFileMd5("");
        request.setFileSize(1000L);
        request.setTotalChunks(5);
        request.setChunkSize(200L);
        request.setOriginalFilename("test.pdf");

        ChunkInitResponse response = chunkUploadService.initChunkUpload(request);

        assertThat(response.getMessage()).contains("参数错误");
    }

    // ==================== uploadChunk ====================

    @Test
    void uploadChunk_正常上传_应写入分片并更新进度() throws Exception {
        MockMultipartFile chunk = new MockMultipartFile(
                "chunk", "chunk", "application/octet-stream",
                "0123456789".getBytes());

        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(1L);
        when(fileInfoService.findByMd5AnyStatus(MD5)).thenReturn(fileInfo);
        when(fileChunkMapper.countUploadedChunks(MD5)).thenReturn(1);

        ChunkUploadResponse response = chunkUploadService.uploadChunk(
                MD5, 0, 5, 10L, chunk);

        assertThat(response.getFileMd5()).isEqualTo(MD5);
        assertThat(response.getChunkIndex()).isEqualTo(0);
        assertThat(response.getUploadedChunks()).isEqualTo(1);
        assertThat(response.getTotalChunks()).isEqualTo(5);
        assertThat(response.getUploadStatus()).isEqualTo("UPLOADING");

        verify(fileChunkMapper).insertOrUpdate(any(FileChunk.class));
        verify(fileInfoService).updateChunkProgress(eq(1L), eq(1), eq(5));
    }

    @Test
    void uploadChunk_重复上传_应幂等() throws Exception {
        MockMultipartFile chunk = new MockMultipartFile(
                "chunk", "chunk", "application/octet-stream",
                "data".getBytes());

        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(1L);
        when(fileInfoService.findByMd5AnyStatus(MD5)).thenReturn(fileInfo);
        when(fileChunkMapper.countUploadedChunks(MD5)).thenReturn(1);

        // 第一次上传
        ChunkUploadResponse r1 = chunkUploadService.uploadChunk(
                MD5, 0, 3, 4L, chunk);
        assertThat(r1.getMessage()).contains("上传成功");

        // 第二次上传同一分片
        ChunkUploadResponse r2 = chunkUploadService.uploadChunk(
                MD5, 0, 3, 4L, chunk);
        assertThat(r2.getMessage()).contains("上传成功");

        // insertOrUpdate 被调用了两次（幂等由 DB 层 ON DUPLICATE KEY UPDATE 保证）
        verify(fileChunkMapper, times(2)).insertOrUpdate(any(FileChunk.class));
    }

    @Test
    void uploadChunk_参数非法_应返回错误() {
        MockMultipartFile chunk = new MockMultipartFile(
                "chunk", "chunk", "application/octet-stream",
                "data".getBytes());

        // fileMd5 为空
        ChunkUploadResponse r1 = chunkUploadService.uploadChunk(
                "", 0, 3, 4L, chunk);
        assertThat(r1.getMessage()).contains("参数错误");

        // chunkIndex 为负数
        ChunkUploadResponse r2 = chunkUploadService.uploadChunk(
                MD5, -1, 3, 4L, chunk);
        assertThat(r2.getMessage()).contains("参数错误");

        // chunkIndex 超出范围
        ChunkUploadResponse r3 = chunkUploadService.uploadChunk(
                MD5, 5, 5, 4L, chunk);
        assertThat(r3.getMessage()).contains("超出范围");

        verify(fileChunkMapper, never()).insertOrUpdate(any());
    }

    // ==================== mergeChunks ====================

    @Test
    void mergeChunks_分片数量不完整_应返回失败() {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(1L);
        fileInfo.setFileName("big.pdf");
        fileInfo.setUploadStatus("READY_TO_MERGE");
        fileInfo.setTotalChunks(5);

        when(fileInfoService.findByMd5AnyStatus(MD5)).thenReturn(fileInfo);

        // 只返回 3 个分片，不满足 totalChunks=5
        List<FileChunk> incompleteChunks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            FileChunk c = new FileChunk();
            c.setChunkIndex(i);
            c.setUploadStatus(FileChunkStatus.UPLOADED.getCode());
            c.setChunkPath("/tmp/exists.part");
            incompleteChunks.add(c);
        }
        when(fileChunkMapper.findByFileMd5(MD5)).thenReturn(incompleteChunks);

        ChunkMergeResponse response = chunkUploadService.mergeChunks(MD5);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getMessage()).contains("分片数量不完整");
    }

    @Test
    void mergeChunks_状态不是READY_TO_MERGE_应返回失败() {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(1L);
        fileInfo.setUploadStatus("UPLOADING");  // 非 READY_TO_MERGE
        fileInfo.setTotalChunks(5);

        when(fileInfoService.findByMd5AnyStatus(MD5)).thenReturn(fileInfo);

        ChunkMergeResponse response = chunkUploadService.mergeChunks(MD5);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getMessage()).contains("状态不允许合并");
    }

    @Test
    void mergeChunks_文件不存在_应返回失败() {
        when(fileInfoService.findByMd5AnyStatus(MD5)).thenReturn(null);

        ChunkMergeResponse response = chunkUploadService.mergeChunks(MD5);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getMessage()).contains("文件不存在");
    }

    @Test
    void mergeChunks_参数为空_应返回失败() {
        ChunkMergeResponse response = chunkUploadService.mergeChunks("");

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getMessage()).contains("参数错误");
    }
}
