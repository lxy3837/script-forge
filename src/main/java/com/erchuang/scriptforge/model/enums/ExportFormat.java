package com.erchuang.scriptforge.model.enums;

/**
 * 导出格式枚举.
 *
 * @author ScriptForge Team
 */
public enum ExportFormat {

    /** Markdown */
    MARKDOWN("Markdown", ".md"),

    /** Word文档 */
    WORD("Word文档", ".docx"),

    /** PDF文档 */
    PDF("PDF文档", ".pdf"),

    /** SRT字幕 */
    SRT("SRT字幕", ".srt"),

    /** 纯文本 */
    PLAIN_TEXT("纯文本", ".txt");

    private final String displayName;
    private final String extension;

    ExportFormat(String displayName, String extension) {
        this.displayName = displayName;
        this.extension = extension;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getExtension() {
        return extension;
    }

    /**
     * 根据扩展名（含点号）查找枚举.
     *
     * @param extension 文件扩展名（如".md"）
     * @return 对应的ExportFormat，找不到返回MARKDOWN
     */
    public static ExportFormat fromExtension(String extension) {
        if (extension == null) {
            return MARKDOWN;
        }
        for (ExportFormat format : values()) {
            if (format.extension.equalsIgnoreCase(extension)) {
                return format;
            }
        }
        return MARKDOWN;
    }
}
