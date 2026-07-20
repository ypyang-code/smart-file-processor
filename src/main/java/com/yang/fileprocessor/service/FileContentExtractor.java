package com.yang.fileprocessor.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文件内容提取服务（Phase 5：内存风险控制）
 *
 * <p>支持 PDF（PDFBox 3.0.1）、Word .docx（Apache POI 5.2.5）、TXT 三种格式。
 * 所有格式均设置了<b>硬上限</b>保护，防止大文件、恶意文件或异常格式导致 OOM。
 *
 * <h3>保护策略总览</h3>
 * <table>
 *   <tr><th>格式</th><th>文件大小上限</th><th>结构上限</th><th>提取字符上限</th></tr>
 *   <tr><td>TXT</td>  <td>MAX_TEXT_BYTES  (10 MB)</td>  <td>—</td>       <td>MAX_EXTRACTED_CHARS</td></tr>
 *   <tr><td>PDF</td>  <td>MAX_PDF_BYTES   (20 MB)</td>  <td>MAX_PDF_PAGES (500 页)</td> <td>MAX_EXTRACTED_CHARS</td></tr>
 *   <tr><td>DOCX</td> <td>MAX_DOCX_BYTES  (20 MB)</td>  <td>MAX_PARAGRAPHS (10000 段)</td> <td>MAX_EXTRACTED_CHARS</td></tr>
 * </table>
 * <p>MAX_EXTRACTED_CHARS = 5,000,000（约 5 MB 纯文本），所有格式共享此上限。
 *
 * <h3>已知边界</h3>
 * <ul>
 *   <li>PDF 文本截断通过 {@link BoundedWriter} + {@code PDFTextStripper.writeText()} 实现，
 *       避免了首先生成全量 String 再截断的内存浪费。</li>
 *   <li>但目前 PDFBox 的 {@code writeText()} 仍会完整遍历 PDF 页面对象树。
 *      超大 PDF（接近 20MB / 500 页上限）的内存占用仍可能较高，
 *      但已在文件大小和页数门禁的控制范围内。</li>
 *   <li>当前不实现解析超时（PDFBox 底层 IO/解析无法被 {@code Future.cancel()} 安全中断）。</li>
 *   <li>解析失败时当前返回安全简短信息（如"文件过大"），完整异常写入 log.error。
 *      {@code file_info.status} 保持原有语义不变。后续可优化为独立 error_reason 字段。</li>
 * </ul>
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
@Service
public class FileContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(FileContentExtractor.class);

    // ======================== 硬上限常量 ========================

    /** TXT 文件最大字节数（超过此值拒绝全文解析） */
    static final long MAX_TEXT_BYTES = 10 * 1024 * 1024;    // 10 MB

    /** PDF 文件最大字节数 */
    static final long MAX_PDF_BYTES = 20 * 1024 * 1024;     // 20 MB

    /** PDF 最大页数 */
    static final int MAX_PDF_PAGES = 500;

    /** DOCX 文件最大字节数 */
    static final long MAX_DOCX_BYTES = 20 * 1024 * 1024;    // 20 MB

    /** DOCX 最大段落数 */
    static final int MAX_PARAGRAPHS = 10_000;

    /** 提取文本最大字符数（所有格式共享，约 5 MB 纯文本） */
    static final int MAX_EXTRACTED_CHARS = 5_000_000;

    // ======================== 公开入口 ========================

    /**
     * 根据文件类型提取文字内容（带内存保护）
     *
     * @param filePath 本地文件绝对路径
     * @param fileType 文件类型（pdf / word / text / 其他）
     * @return 提取的文本；超限时截断；失败时返回简短安全提示
     */
    public String extractContent(String filePath, String fileType) {
        if (filePath == null || fileType == null) {
            return "内容提取失败: 参数为空";
        }
        try {
            switch (fileType) {
                case "pdf":
                    return extractFromPdf(filePath);
                case "word":
                    return extractFromWord(filePath);
                case "text":
                    return extractFromTxt(filePath);
                default:
                    return "不支持提取该文件类型的内容";
            }
        } catch (Exception e) {
            // 兜底：catch 所有未在子方法内处理的异常
            log.error("内容提取未预期异常: filePath={}, fileType={}", filePath, fileType, e);
            return "内容提取失败: 解析异常";
        }
    }

    // ======================== TXT 解析 ========================

    /**
     * 流式提取 TXT 文本（BufferedReader + char buffer + 截断保护）
     *
     * <p>使用 {@link BufferedReader} 以 8 KB char buffer 循环读取，
     * 不将整个文件读入 byte[] 再转 String，避免额外内存拷贝。
     * 达到 {@link #MAX_EXTRACTED_CHARS} 后立即停止读取并返回截断文本。
     */
    private String extractFromTxt(String filePath) {
        File file = new File(filePath);

        // 1. 文件存在性检查
        if (!file.exists()) {
            log.warn("TXT 文件不存在: {}", filePath);
            return "内容提取失败: 文件不存在";
        }

        // 2. 文件大小门禁
        long fileSize = file.length();
        if (fileSize > MAX_TEXT_BYTES) {
            log.warn("TXT 文件过大 {} 字节（上限 {} 字节），将截断读取: {}",
                    fileSize, MAX_TEXT_BYTES, filePath);
        }

        // 3. 流式读取（BufferedReader + char buffer）
        StringBuilder content = new StringBuilder(8192);
        int totalChars = 0;
        boolean truncated = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8), 8192)) {
            char[] cbuf = new char[8192];
            int charsRead;
            while ((charsRead = reader.read(cbuf)) != -1) {
                if (totalChars >= MAX_EXTRACTED_CHARS) {
                    truncated = true;
                    break;
                }
                int toCopy = Math.min(charsRead, MAX_EXTRACTED_CHARS - totalChars);
                content.append(cbuf, 0, toCopy);
                totalChars += toCopy;
            }
        } catch (IOException e) {
            log.error("TXT 读取异常: filePath={}", filePath, e);
            return "内容提取失败: 文件读取异常";
        }

        if (truncated) {
            log.warn("TXT 文本截断: filePath={}, 文件大小={} 字节, 截断位置={} 字符",
                    filePath, fileSize, MAX_EXTRACTED_CHARS);
        }
        log.debug("TXT 提取完成: filePath={}, 字符数={}, 截断={}", filePath, totalChars, truncated);
        return content.toString();
    }

    // ======================== PDF 解析 ========================

    /**
     * 提取 PDF 文本（文件大小门禁 + 页数门禁 + BoundedWriter 截断）
     *
     * <p>使用 {@code PDFTextStripper.writeText(document, boundedWriter)} 将文本写入
     * {@link BoundedWriter}，避免首先生成完整 String 再截断。
     * 在文件大小和页数门禁范围内，即使文本超限，BoundedWriter 只保留上限内的字符。
     */
    private String extractFromPdf(String filePath) {
        File file = new File(filePath);

        // 1. 文件存在性检查
        if (!file.exists()) {
            log.warn("PDF 文件不存在: {}", filePath);
            return "内容提取失败: 文件不存在";
        }

        // 2. 文件大小门禁
        long fileSize = file.length();
        if (fileSize > MAX_PDF_BYTES) {
            log.warn("PDF 文件过大（{} 字节，上限 {} 字节），拒绝解析: {}",
                    fileSize, MAX_PDF_BYTES, filePath);
            return "内容提取失败: 文件过大";
        }

        // 3. 加载并校验页数
        try (PDDocument document = Loader.loadPDF(file)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                log.debug("PDF 页数为 0: {}", filePath);
                return "";
            }
            if (pageCount > MAX_PDF_PAGES) {
                log.warn("PDF 页数过多（{} 页，上限 {} 页），拒绝解析: {}",
                        pageCount, MAX_PDF_PAGES, filePath);
                return "内容提取失败: 文件过大";
            }

            // 4. 使用 BoundedWriter 提取文本（避免全量 String）
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            BoundedWriter boundedWriter = new BoundedWriter(MAX_EXTRACTED_CHARS);
            stripper.writeText(document, boundedWriter);

            if (boundedWriter.isTruncated()) {
                log.warn("PDF 文本截断: filePath={}, 文件大小={} 字节, 页数={}, 截断位置={} 字符",
                        filePath, fileSize, pageCount, MAX_EXTRACTED_CHARS);
            }
            log.debug("PDF 提取完成: filePath={}, 页数={}, 字符数={}, 截断={}",
                    filePath, pageCount, boundedWriter.getTotalChars(), boundedWriter.isTruncated());
            return boundedWriter.getText();

        } catch (IOException e) {
            log.error("PDF 解析 IO 异常（可能文件损坏或格式异常）: filePath={}, fileSize={}",
                    filePath, fileSize, e);
            return "内容提取失败: 文件格式异常";
        } catch (RuntimeException e) {
            log.error("PDF 解析运行时异常: filePath={}, fileSize={}",
                    filePath, fileSize, e);
            return "内容提取失败: 解析异常";
        }
    }

    // ======================== DOCX 解析 ========================

    /**
     * 提取 DOCX 文本（文件大小门禁 + 段落数门禁 + 字符数截断）
     */
    private String extractFromWord(String filePath) {
        File file = new File(filePath);

        // 1. 文件存在性检查
        if (!file.exists()) {
            log.warn("DOCX 文件不存在: {}", filePath);
            return "内容提取失败: 文件不存在";
        }

        // 2. 文件大小门禁
        long fileSize = file.length();
        if (fileSize > MAX_DOCX_BYTES) {
            log.warn("DOCX 文件过大（{} 字节，上限 {} 字节），拒绝解析: {}",
                    fileSize, MAX_DOCX_BYTES, filePath);
            return "内容提取失败: 文件过大";
        }

        // 3. 解析
        try (InputStream is = new BufferedInputStream(new FileInputStream(file), 8192);
             XWPFDocument document = new XWPFDocument(is)) {

            List<XWPFParagraph> paragraphs = document.getParagraphs();
            if (paragraphs.isEmpty()) {
                log.debug("DOCX 段落为空: {}", filePath);
                return "";
            }

            int paragraphCount = paragraphs.size();
            boolean paraTruncated = paragraphCount > MAX_PARAGRAPHS;

            StringBuilder content = new StringBuilder(8192);
            int totalChars = 0;
            boolean charTruncated = false;

            int limit = Math.min(paragraphCount, MAX_PARAGRAPHS);
            for (int i = 0; i < limit; i++) {
                String text = paragraphs.get(i).getText();
                if (text == null || text.isEmpty()) {
                    continue;
                }
                if (totalChars >= MAX_EXTRACTED_CHARS) {
                    charTruncated = true;
                    break;
                }
                int remaining = MAX_EXTRACTED_CHARS - totalChars;
                if (text.length() > remaining) {
                    content.append(text, 0, remaining);
                    totalChars += remaining;
                    charTruncated = true;
                    break;
                }
                content.append(text).append('\n');
                totalChars += text.length() + 1; // +1 for \n
            }

            if (paraTruncated) {
                log.warn("DOCX 段落截断: filePath={}, 实际段落数={}, 上限={}",
                        filePath, paragraphCount, MAX_PARAGRAPHS);
            }
            if (charTruncated) {
                log.warn("DOCX 字符截断: filePath={}, 文件大小={} 字节, 截断位置={} 字符",
                        filePath, fileSize, MAX_EXTRACTED_CHARS);
            }
            log.debug("DOCX 提取完成: filePath={}, 段落数={}/{}, 字符数={}, 截断={}",
                    filePath, limit, paragraphCount, totalChars, paraTruncated || charTruncated);
            return content.toString();

        } catch (IOException e) {
            log.error("DOCX 解析 IO 异常（可能文件损坏或格式异常）: filePath={}, fileSize={}",
                    filePath, fileSize, e);
            return "内容提取失败: 文件格式异常";
        } catch (RuntimeException e) {
            log.error("DOCX 解析运行时异常: filePath={}, fileSize={}",
                    filePath, fileSize, e);
            return "内容提取失败: 解析异常";
        }
    }

    // ======================== BoundedWriter（PDF 专用） ========================

    /**
     * 带字符数上限的 {@link Writer} 实现。
     *
     * <p>仅重写 {@link Writer} 的抽象方法 {@code write(char[], int, int)}、
     * {@code flush()} 和 {@code close()}。
     * {@code write(String)} 和 {@code write(String, int, int)} 的默认实现
     * 均委托到 {@code write(char[], int, int)}，因此只需重写该方法即可拦截所有写入路径。
     *
     * <p>用于 {@code PDFTextStripper.writeText(document, boundedWriter)}，
     * 避免首先生成完整 String 再截断，节省中间内存分配。
     */
    static class BoundedWriter extends Writer {

        private final StringBuilder sb;
        private final int maxChars;
        private int totalChars;

        BoundedWriter(int maxChars) {
            this.sb = new StringBuilder(Math.min(maxChars, 8192));
            this.maxChars = maxChars;
            this.totalChars = 0;
            this.lock = sb; // Writer 内置锁，使用 StringBuilder 自身作为锁对象
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            if (totalChars >= maxChars || len <= 0) {
                return;
            }
            int toWrite = Math.min(len, maxChars - totalChars);
            sb.append(cbuf, off, toWrite);
            totalChars += toWrite;
        }

        @Override
        public void flush() {
            // no-op: StringBuilder 不需要 flush
        }

        @Override
        public void close() {
            // no-op: StringBuilder 不需要 close；生命周期由调用方管理
        }

        String getText() {
            return sb.toString();
        }

        boolean isTruncated() {
            return totalChars >= maxChars;
        }

        int getTotalChars() {
            return totalChars;
        }
    }
}
