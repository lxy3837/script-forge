package com.erchuang.scriptforge.service;

import com.erchuang.scriptforge.export.ExportEngine;
import com.erchuang.scriptforge.infra.FileUtils;
import com.erchuang.scriptforge.model.enums.ExportFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 导出服务——提供多格式导出的统一入口.
 *
 * @author ScriptForge Team
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final ExportEngine exportEngine;

    @Value("${app.export-dir}")
    private String exportDir;

    public ExportService(ExportEngine exportEngine) {
        this.exportEngine = exportEngine;
    }

    /**
     * 导出内容到指定格式.
     *
     * @param content      要导出的内容
     * @param format       导出格式
     * @param fileName     文件名（不含路径）
     * @return 导出后的完整文件路径
     */
    public String export(String content, ExportFormat format, String fileName) {
        FileUtils.ensureDirectoryExists(exportDir);
        String safeFileName = FileUtils.sanitizeFileName(fileName);
        String outputPath = exportDir + "/" + safeFileName + format.getExtension();
        log.info("Exporting to {}, format={}", outputPath, format);
        return exportEngine.export(content, format, outputPath);
    }
}
