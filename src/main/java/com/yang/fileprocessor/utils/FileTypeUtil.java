package com.yang.fileprocessor.utils;

/**
 * 文件类型工具类
 * <p>
 * 统一根据文件名扩展名判断文件类型，消除项目中多处重复的 getFileType 逻辑。
 * 支持 null 安全和无扩展名回退，返回小写类型字符串。
 *
 * <h3>类型映射</h3>
 * <ul>
 *   <li>pdf → {@code "pdf"}</li>
 *   <li>doc/docx → {@code "word"}</li>
 *   <li>jpg/jpeg/png → {@code "image"}</li>
 *   <li>txt → {@code "text"}</li>
 *   <li>其他 / null / 无扩展名 → {@code "other"}</li>
 * </ul>
 *
 * @author yangyunpu
 * @since 2026-07-15
 */
public final class FileTypeUtil {

    private FileTypeUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 根据文件名获取文件类型
     *
     * @param fileName 原始文件名（可为 null）
     * @return 小写类型字符串：pdf / word / image / text / other
     */
    public static String getFileType(String fileName) {
        if (fileName == null) {
            return "other";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "other";
        }
        String ext = fileName.substring(dot + 1).toLowerCase();
        switch (ext) {
            case "pdf":
                return "pdf";
            case "doc":
            case "docx":
                return "word";
            case "jpg":
            case "jpeg":
            case "png":
                return "image";
            case "txt":
                return "text";
            default:
                return "other";
        }
    }
}
