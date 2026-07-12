package com.erchuang.scriptforge.export;

import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * PDF导出引擎——使用Apache PDFBox生成PDF文档.
 *
 * @author ScriptForge Team
 */
@Component
public class PdfEngine implements ExportEngine.Engine {

    private static final Logger log = LoggerFactory.getLogger(PdfEngine.class);

    /** 页面边距 */
    private static final float MARGIN = 50;
    /** 行高 */
    private static final float LINE_HEIGHT = 15;
    /** 字体大小 */
    private static final float FONT_SIZE = 11;
    /** 标题字号 */
    private static final float TITLE_SIZE = 18;

    @Override
    public void export(String content, String outputPath) {
        String filePath = ensureExtension(outputPath);

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(font, FONT_SIZE);
                contentStream.setLeading(LINE_HEIGHT);

                float yPosition = page.getMediaBox().getHeight() - MARGIN;
                float pageWidth = page.getMediaBox().getWidth() - 2 * MARGIN;

                String[] lines = content.split("\n");

                for (String line : lines) {
                    if (yPosition < MARGIN) {
                        // 新建页面
                        contentStream.endText();
                        page = new PDPage();
                        document.addPage(page);
                        contentStream.close();
                        // 需要重新创建contentStream（此处简化为仅支持单页）
                        // 实际生产环境中应使用更好的分页逻辑
                        yPosition = page.getMediaBox().getHeight() - MARGIN;
                    }

                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        yPosition -= LINE_HEIGHT;
                        continue;
                    }

                    if (trimmed.startsWith("# ")) {
                        contentStream.setFont(font, TITLE_SIZE);
                        drawLine(contentStream, trimmed.substring(2).trim(), pageWidth, yPosition);
                        contentStream.setFont(font, FONT_SIZE);
                        yPosition -= LINE_HEIGHT * 2;
                    } else {
                        // 自动换行处理
                        List<String> wrappedLines = wrapText(trimmed, font, FONT_SIZE, pageWidth);
                        for (String wrappedLine : wrappedLines) {
                            if (yPosition < MARGIN) {
                                break;
                            }
                            drawLine(contentStream, wrappedLine, pageWidth, yPosition);
                            yPosition -= LINE_HEIGHT;
                        }
                    }
                }
                contentStream.endText();
            }

            document.save(new File(filePath));
            log.debug("PDF exported to: {}", filePath);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.EXPORT_FAILED,
                    "Failed to export PDF: " + e.getMessage(), e);
        }
    }

    private void drawLine(PDPageContentStream contentStream, String text,
                           float pageWidth, float yPosition) throws IOException {
        contentStream.newLineAtOffset(MARGIN, yPosition);
        contentStream.showText(text);
        contentStream.newLineAtOffset(-MARGIN, -yPosition);
    }

    private List<String> wrapText(String text, PDType1Font font, float fontSize, float pageWidth) {
        // 简化实现：按字符数估算换行
        float charWidth = font.getAverageFontWidth() / 1000f * fontSize;
        int charsPerLine = Math.max(1, (int) (pageWidth / charWidth));

        List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += charsPerLine) {
            int end = Math.min(i + charsPerLine, text.length());
            result.add(text.substring(i, end));
        }
        return result;
    }

    private String ensureExtension(String outputPath) {
        if (!outputPath.toLowerCase().endsWith(".pdf")) {
            return outputPath + ".pdf";
        }
        return outputPath;
    }
}
