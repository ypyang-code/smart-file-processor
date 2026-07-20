package com.yang.fileprocessor.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FileContentExtractor 单元测试（使用真实 PDFBox/POI 库，无 Mockito）
 *
 * <p>所有文件在 {@code @TempDir} 中创建，测试结束后自动清理。
 * 不使用 Spring 上下文，直接实例化 {@link FileContentExtractor}。
 *
 * <h3>覆盖场景</h3>
 * <ol>
 *   <li>正常 TXT 提取</li>
 *   <li>空 TXT 文件</li>
 *   <li>TXT 字符数超过 MAX_EXTRACTED_CHARS → 截断</li>
 *   <li>正常 PDF 提取（PDFBox 生成）</li>
 *   <li>PDF 0 字节（损坏）→ 安全错误消息</li>
 *   <li>PDF 随机字节（损坏格式）→ 安全错误消息</li>
 *   <li>正常 DOCX 提取（POI 生成）</li>
 *   <li>DOCX 0 字节（损坏）→ 安全错误消息</li>
 *   <li>DOCX 随机字节（损坏格式）→ 安全错误消息</li>
 *   <li>不支持的文件类型 → 默认消息</li>
 *   <li>null 参数 → 安全消息</li>
 *   <li>文件不存在 → 安全消息</li>
 * </ol>
 *
 * @author yangyunpu
 * @since 2026-07-16
 */
class FileContentExtractorTest {

    private FileContentExtractor extractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        extractor = new FileContentExtractor();
    }

    // ==================== TXT 正常提取 ====================

    @Test
    void extractFromTxt_正常小文件_应完整提取() throws Exception {
        Path txtFile = tempDir.resolve("hello.txt");
        Files.writeString(txtFile, "Hello World\n第二行文本\n", StandardCharsets.UTF_8);

        String result = extractor.extractContent(txtFile.toString(), "text");

        assertThat(result).contains("Hello World");
        assertThat(result).contains("第二行文本");
    }

    @Test
    void extractFromTxt_空文件_应返回空字符串() throws Exception {
        Path txtFile = tempDir.resolve("empty.txt");
        Files.writeString(txtFile, "", StandardCharsets.UTF_8);

        String result = extractor.extractContent(txtFile.toString(), "text");

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void extractFromTxt_超过MAX_EXTRACTED_CHARS_应截断() throws Exception {
        // 生成 > 5M 字符的文本文件（使用重复单字符，磁盘压缩小）
        Path txtFile = tempDir.resolve("large.txt");
        int targetChars = FileContentExtractor.MAX_EXTRACTED_CHARS + 100_000;
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new BufferedOutputStream(new FileOutputStream(txtFile.toFile())), StandardCharsets.UTF_8)) {
            // 每行 1000 个 'A' + \n，减少 write 调用次数
            char[] line = new char[1001];
            for (int i = 0; i < 1000; i++) {
                line[i] = 'A';
            }
            line[1000] = '\n';
            int written = 0;
            while (written < targetChars) {
                writer.write(line);
                written += 1001;
            }
        }

        String result = extractor.extractContent(txtFile.toString(), "text");

        // 提取字符数不应超过 MAX_EXTRACTED_CHARS（允许少量超出因 char buffer 粒度）
        assertThat(result.length()).isLessThanOrEqualTo(FileContentExtractor.MAX_EXTRACTED_CHARS + 8192);
    }

    // ==================== TXT 异常场景 ====================

    @Test
    void extractFromTxt_文件不存在_应返回安全错误消息() {
        String result = extractor.extractContent(tempDir.resolve("no_such.txt").toString(), "text");

        assertThat(result).contains("内容提取失败");
        assertThat(result).doesNotContain("java.io");  // 不泄露异常细节
    }

    // ==================== PDF 正常提取 ====================

    @Test
    void extractFromPdf_正常PDF_应提取文本() throws Exception {
        Path pdfFile = tempDir.resolve("hello.pdf");
        createSimplePdf(pdfFile, "Hello PDF World\nPage Two Content\n");

        String result = extractor.extractContent(pdfFile.toString(), "pdf");

        assertThat(result).contains("Hello PDF World");
    }

    @Test
    void extractFromPdf_单页PDF_应正确提取() throws Exception {
        Path pdfFile = tempDir.resolve("single.pdf");
        // 创建最简单 PDF
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdfFile.toFile());
        }

        String result = extractor.extractContent(pdfFile.toString(), "pdf");

        assertThat(result).isNotNull();
        // 空页面可能有空白字符，不应是错误消息
        assertThat(result).doesNotContain("内容提取失败");
    }

    // ==================== PDF 异常场景 ====================

    @Test
    void extractFromPdf_空文件损坏_应返回安全错误消息() throws Exception {
        Path pdfFile = tempDir.resolve("corrupt.pdf");
        Files.writeString(pdfFile, "", StandardCharsets.UTF_8);

        String result = extractor.extractContent(pdfFile.toString(), "pdf");

        assertThat(result).contains("内容提取失败");
        assertThat(result).doesNotContain("java.io");
    }

    @Test
    void extractFromPdf_随机字节损坏_应返回安全错误消息() throws Exception {
        Path pdfFile = tempDir.resolve("bad.pdf");
        byte[] randomBytes = new byte[1024];
        for (int i = 0; i < randomBytes.length; i++) {
            randomBytes[i] = (byte) (i % 256);
        }
        Files.write(pdfFile, randomBytes);

        String result = extractor.extractContent(pdfFile.toString(), "pdf");

        // 损坏 PDF 应返回安全的错误消息，不应抛异常
        assertThat(result).contains("内容提取失败");
        assertThat(result).doesNotContain("java.io");
        assertThat(result).doesNotContain("IOException");
        assertThat(result).doesNotContain("RuntimeException");
    }

    @Test
    void extractFromPdf_文件不存在_应返回安全错误消息() {
        String result = extractor.extractContent(tempDir.resolve("no.pdf").toString(), "pdf");

        assertThat(result).contains("内容提取失败");
    }

    // ==================== DOCX 正常提取 ====================

    @Test
    void extractFromWord_正常DOCX_应提取文本() throws Exception {
        Path docxFile = tempDir.resolve("hello.docx");
        createSimpleDocx(docxFile, "Hello DOCX World\n第二段落内容\n");

        String result = extractor.extractContent(docxFile.toString(), "word");

        assertThat(result).contains("Hello DOCX World");
    }

    @Test
    void extractFromWord_空DOCX_应返回空字符串() throws Exception {
        Path docxFile = tempDir.resolve("empty.docx");
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream fos = new FileOutputStream(docxFile.toFile())) {
            doc.write(fos);
        }

        String result = extractor.extractContent(docxFile.toString(), "word");

        assertThat(result).isNotNull();
        assertThat(result).doesNotContain("内容提取失败");
    }

    // ==================== DOCX 异常场景 ====================

    @Test
    void extractFromWord_空文件损坏_应返回安全错误消息() throws Exception {
        Path docxFile = tempDir.resolve("bad.docx");
        Files.writeString(docxFile, "", StandardCharsets.UTF_8);

        String result = extractor.extractContent(docxFile.toString(), "word");

        assertThat(result).contains("内容提取失败");
        assertThat(result).doesNotContain("java.io");
    }

    @Test
    void extractFromWord_随机字节损坏_应返回安全错误消息() throws Exception {
        Path docxFile = tempDir.resolve("random.docx");
        byte[] randomBytes = new byte[1024];
        for (int i = 0; i < randomBytes.length; i++) {
            randomBytes[i] = (byte) (i % 256);
        }
        Files.write(docxFile, randomBytes);

        String result = extractor.extractContent(docxFile.toString(), "word");

        assertThat(result).contains("内容提取失败");
        assertThat(result).doesNotContain("IOException");
        assertThat(result).doesNotContain("RuntimeException");
    }

    @Test
    void extractFromWord_文件不存在_应返回安全错误消息() {
        String result = extractor.extractContent(tempDir.resolve("no.docx").toString(), "word");

        assertThat(result).contains("内容提取失败");
    }

    // ==================== 其他格式 / null ====================

    @Test
    void extractContent_不支持的文件类型_应返回默认消息() {
        String result = extractor.extractContent("/tmp/test.png", "image");

        assertThat(result).contains("不支持提取");
    }

    @Test
    void extractContent_path为null_应返回安全消息() {
        String result = extractor.extractContent(null, "text");

        assertThat(result).contains("参数为空");
    }

    @Test
    void extractContent_type为null_应返回安全消息() {
        String result = extractor.extractContent("/tmp/test.txt", null);

        assertThat(result).contains("参数为空");
    }

    // ==================== BoundedWriter 单元 ====================

    @Test
    void boundedWriter_未达上限_应完整写入() {
        FileContentExtractor.BoundedWriter writer = new FileContentExtractor.BoundedWriter(100);

        writer.write(new char[]{'A', 'B', 'C', 'D', 'E'}, 0, 5);

        assertThat(writer.getText()).isEqualTo("ABCDE");
        assertThat(writer.isTruncated()).isFalse();
        assertThat(writer.getTotalChars()).isEqualTo(5);
    }

    @Test
    void boundedWriter_达到上限_应截断() {
        FileContentExtractor.BoundedWriter writer = new FileContentExtractor.BoundedWriter(5);

        writer.write(new char[]{'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J'}, 0, 10);

        assertThat(writer.getText()).isEqualTo("ABCDE");
        assertThat(writer.isTruncated()).isTrue();
        assertThat(writer.getTotalChars()).isEqualTo(5);
    }

    @Test
    void boundedWriter_分批写入达上限_应累计截断() {
        FileContentExtractor.BoundedWriter writer = new FileContentExtractor.BoundedWriter(5);

        writer.write(new char[]{'A', 'B', 'C'}, 0, 3);
        writer.write(new char[]{'D', 'E', 'F', 'G'}, 0, 4);

        assertThat(writer.getText()).isEqualTo("ABCDE");
        assertThat(writer.isTruncated()).isTrue();
    }

    // ==================== 工具方法 ====================

    /**
     * 创建简单 PDF 用于测试
     */
    private void createSimplePdf(Path path, String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (String line : text.split("\n")) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(line);
                    cs.endText();
                }
            }
            doc.save(path.toFile());
        }
    }

    /**
     * 创建简单 DOCX 用于测试
     */
    private void createSimpleDocx(Path path, String text) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream fos = new FileOutputStream(path.toFile())) {
            for (String line : text.split("\n")) {
                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                run.setText(line);
            }
            doc.write(fos);
        }
    }
}
