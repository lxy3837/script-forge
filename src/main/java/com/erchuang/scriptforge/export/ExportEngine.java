package com.erchuang.scriptforge.export;

import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.erchuang.scriptforge.infra.FileUtils;
import com.erchuang.scriptforge.model.enums.ExportFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;

/**
 * 导出调度引擎——根据导出格式选择对应的引擎执行导出.
 *
 * @author ScriptForge Team
 */
@Component
public class ExportEngine {

    private static final Logger log = LoggerFactory.getLogger(ExportEngine.class);

    private final Map<ExportFormat, Engine> engines = new EnumMap<>(ExportFormat.class);

    public ExportEngine(MarkdownEngine markdownEngine, WordEngine wordEngine,
                        PdfEngine pdfEngine, SrtEngine srtEngine) {
        engines.put(ExportFormat.MARKDOWN, markdownEngine);
        engines.put(ExportFormat.WORD, wordEngine);
        engines.put(ExportFormat.PDF, pdfEngine);
        engines.put(ExportFormat.SRT, srtEngine);
        engines.put(ExportFormat.PLAIN_TEXT, markdownEngine); // 纯文本复用Markdown引擎
    }

    /**
     * 根据格式导出文件.
     *
     * @param content    导出内容（Markdown格式的原始内容）
     * @param format     导出格式
     * @param outputPath 输出文件路径
     * @return 导出后的完整文件路径
     */
    public String export(String content, ExportFormat format, String outputPath) {
        Engine engine = engines.get(format);
        if (engine == null) {
            throw new BusinessException(ErrorCode.EXPORT_FAILED,
                    "Unsupported export format: " + format);
        }

        // 检查磁盘空间
        FileUtils.checkDiskSpace(outputPath);

        // 确保目标目录存在
        FileUtils.ensureDirectoryExists(Paths.get(outputPath).getParent().toString());

        log.info("Exporting to {} format, output: {}", format.getDisplayName(), outputPath);

        try {
            engine.export(content, outputPath);
            log.info("Export completed: {}", outputPath);
            return outputPath;
        } catch (Exception e) {
            log.error("Export failed for format {}: {}", format, e.getMessage(), e);
            // 降级：尝试导出为纯文本
            if (format != ExportFormat.PLAIN_TEXT && format != ExportFormat.MARKDOWN) {
                log.warn("Falling back to plain text export");
                String fallbackPath = FileUtils.getFileNameWithoutExtension(outputPath) + ".txt";
                try {
                    engines.get(ExportFormat.PLAIN_TEXT).export(content, fallbackPath);
                    return fallbackPath;
                } catch (Exception fallbackError) {
                    log.error("Fallback export also failed", fallbackError);
                }
            }
            throw new BusinessException(ErrorCode.EXPORT_FAILED,
                    "导出失败: " + e.getMessage(), e);
        }
    }

    /**
     * 引擎接口.
     */
    public interface Engine {
        /**
         * 执行导出.
         *
         * @param content    导出内容
         * @param outputPath 输出路径
         * @throws Exception 导出异常
         */
        void export(String content, String outputPath) throws Exception;
    }
}
