package com.erchuang.scriptforge.export;

import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

/**
 * Word/docx导出引擎——使用Apache POI生成docx文档.
 *
 * @author ScriptForge Team
 */
@Component
public class WordEngine implements ExportEngine.Engine {

    private static final Logger log = LoggerFactory.getLogger(WordEngine.class);

    /** 正文字号 */
    private static final int FONT_SIZE_BODY = 11;
    /** 标题字号 */
    private static final int FONT_SIZE_H1 = 18;
    /** 二级标题字号 */
    private static final int FONT_SIZE_H2 = 14;

    @Override
    public void export(String content, String outputPath) {
        String filePath = ensureExtension(outputPath);
        try (XWPFDocument document = new XWPFDocument();
             FileOutputStream fos = new FileOutputStream(filePath)) {

            // 创建正文样式
            XWPFStyles styles = document.createStyles();
            // 处理内容
            processContent(document, content);

            document.write(fos);
            log.debug("Word document exported to: {}", filePath);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.EXPORT_FAILED,
                    "Failed to export Word document: " + e.getMessage(), e);
        }
    }

    private void processContent(XWPFDocument document, String content) {
        String[] lines = content.split("\n");
        XWPFParagraph currentPara = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                document.createParagraph();
                continue;
            }

            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();

            // 标题处理
            if (trimmed.startsWith("# ")) {
                run.setFontSize(FONT_SIZE_H1);
                run.setBold(true);
                run.setText(trimmed.substring(2).trim());
            } else if (trimmed.startsWith("## ")) {
                run.setFontSize(FONT_SIZE_H2);
                run.setBold(true);
                run.setText(trimmed.substring(3).trim());
            } else if (trimmed.startsWith("### ")) {
                run.setFontSize(12);
                run.setBold(true);
                run.setText(trimmed.substring(4).trim());
            } else {
                run.setFontSize(FONT_SIZE_BODY);
                run.setText(trimmed);
            }
        }
    }

    private String ensureExtension(String outputPath) {
        if (!outputPath.toLowerCase().endsWith(".docx")) {
            return outputPath + ".docx";
        }
        return outputPath;
    }
}
