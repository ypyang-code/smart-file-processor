package com.yang.fileprocessor.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.apache.pdfbox.Loader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 文件内容提取服务
 * 支持 PDF、Word(.docx)、TXT 三种格式
 */
@Service
public class FileContentExtractor {

    /**
     * 根据文件类型提取文字内容
     */
    public String extractContent(String filePath, String fileType) {
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
            return "内容提取失败：" + e.getMessage();
        }
    }

    // 提取 PDF 文字
    private String extractFromPdf(String filePath) throws Exception {
        try (PDDocument document = Loader.loadPDF(new File(filePath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    // 提取 Word(.docx) 文字
    private String extractFromWord(String filePath) throws Exception {
        StringBuilder content = new StringBuilder();
        try (InputStream is = new FileInputStream(filePath);
             XWPFDocument document = new XWPFDocument(is)) {
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            for (XWPFParagraph paragraph : paragraphs) {
                content.append(paragraph.getText()).append("\n");
            }
        }
        return content.toString();
    }

    // 提取 TXT 文字
    private String extractFromTxt(String filePath) throws Exception {
        StringBuilder content = new StringBuilder();
        try (InputStream is = new FileInputStream(filePath)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = is.read(buffer)) != -1) {
                content.append(new String(buffer, 0, len, "UTF-8"));
            }
        }
        return content.toString();
    }
}