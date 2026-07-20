package com.yang.fileprocessor.controller;

import com.yang.fileprocessor.dto.PageResult;
import com.yang.fileprocessor.entity.FileInfo;
import com.yang.fileprocessor.exception.GlobalExceptionHandler;
import com.yang.fileprocessor.service.FileInfoService;
import com.yang.fileprocessor.service.FileUploadProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FileController 单元测试（纯 Mockito，无 Spring 上下文）
 * <p>
 * 使用 {@link MockMvcBuilders#standaloneSetup} 避免加载 MyBatis / DataSource 等持久层组件。
 * 全局异常处理器 {@link GlobalExceptionHandler} 手动添加，验证异常转义行为。
 */
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock
    private FileInfoService fileInfoService;

    @Mock
    private FileUploadProducer fileUploadProducer;

    @InjectMocks
    private FileController fileController;

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;

    private FileInfo sampleFile;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(fileController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        ReflectionTestUtils.setField(fileController, "uploadDir", tempDir.toString());

        sampleFile = new FileInfo();
        sampleFile.setId(1L);
        sampleFile.setFileName("test.pdf");
        sampleFile.setFileType("pdf");
        sampleFile.setFileSize(1024L);
        sampleFile.setStatus(0);
        sampleFile.setUploadStatus("UPLOADED");
        sampleFile.setParseStatus("NOT_PARSED");
    }

    // ==================== POST /upload ====================

    @Test
    void upload_正常文件_应返回成功() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf",
                "dummy content".getBytes());

        when(fileInfoService.saveFileInfo(eq("test.pdf"), eq("pdf"), eq(13L)))
                .thenReturn(sampleFile);
        doNothing().when(fileUploadProducer).sendUploadTask(any());

        mockMvc.perform(multipart("/api/file/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(containsString("fileId=1")));

        verify(fileUploadProducer).sendUploadTask(any());
    }

    @Test
    void upload_MQ发送失败_全局异常处理器应返回错误() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf",
                "dummy content".getBytes());

        when(fileInfoService.saveFileInfo(anyString(), anyString(), anyLong()))
                .thenReturn(sampleFile);
        doThrow(new AmqpException("Broker 不可用"))
                .when(fileUploadProducer).sendUploadTask(any());

        mockMvc.perform(multipart("/api/file/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value(containsString("消息队列异常")));
    }

    // ==================== GET /list ====================

    @Test
    void list_无参数_应返回数组结构向后兼容() throws Exception {
        List<FileInfo> list = new ArrayList<>();
        list.add(sampleFile);
        FileInfo f2 = new FileInfo();
        f2.setId(2L);
        f2.setFileName("doc.docx");
        list.add(f2);

        when(fileInfoService.getAll()).thenReturn(list);

        mockMvc.perform(get("/api/file/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("test.pdf"));

        verify(fileInfoService).getAll();
        verify(fileInfoService, never()).getPage(anyInt(), anyInt());
    }

    @Test
    void list_带分页参数_应返回PageResult结构() throws Exception {
        List<FileInfo> pageList = new ArrayList<>();
        pageList.add(sampleFile);

        PageResult<FileInfo> pageResult = new PageResult<>();
        pageResult.setList(pageList);
        pageResult.setTotal(10);
        pageResult.setPage(2);
        pageResult.setSize(5);
        pageResult.setTotalPages(2);

        when(fileInfoService.getPage(2, 5)).thenReturn(pageResult);

        mockMvc.perform(get("/api/file/list")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.list", hasSize(1)))
                .andExpect(jsonPath("$.data.total").value(10))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        verify(fileInfoService).getPage(2, 5);
        verify(fileInfoService, never()).getAll();
    }

    @Test
    void list_page小于1_应自动修正为1() throws Exception {
        PageResult<FileInfo> pageResult = new PageResult<>();
        pageResult.setList(new ArrayList<>());
        pageResult.setTotal(0);
        pageResult.setPage(1);
        pageResult.setSize(5);
        pageResult.setTotalPages(0);

        when(fileInfoService.getPage(1, 5)).thenReturn(pageResult);

        mockMvc.perform(get("/api/file/list")
                        .param("page", "0")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1));

        verify(fileInfoService).getPage(1, 5);
    }

    @Test
    void list_size大于100_应自动截断为100() throws Exception {
        PageResult<FileInfo> pageResult = new PageResult<>();
        pageResult.setList(new ArrayList<>());
        pageResult.setTotal(0);
        pageResult.setPage(1);
        pageResult.setSize(100);
        pageResult.setTotalPages(0);

        when(fileInfoService.getPage(1, 100)).thenReturn(pageResult);

        mockMvc.perform(get("/api/file/list")
                        .param("page", "1")
                        .param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));

        verify(fileInfoService).getPage(1, 100);
    }

    // ==================== GET /{id} ====================

    @Test
    void getById_文件存在_应返回文件信息() throws Exception {
        when(fileInfoService.getById(1L)).thenReturn(sampleFile);

        mockMvc.perform(get("/api/file/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.fileName").value("test.pdf"));
    }

    @Test
    void getById_文件不存在_应返回null数据() throws Exception {
        when(fileInfoService.getById(999L)).thenReturn(null);

        mockMvc.perform(get("/api/file/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
