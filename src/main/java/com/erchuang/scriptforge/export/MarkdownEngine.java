package com.erchuang.scriptforge.export;

import com.erchuang.scriptforge.infra.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Markdown/纯文本导出引擎.
 *
 * @author ScriptForge Team
 */
@Component
public class MarkdownEngine implements ExportEngine.Engine {

    private static final Logger log = LoggerFactory.getLogger(MarkdownEngine.class);

    /**
     * 导出为Markdown文件（直接写入原始内容）.
     *
     * @param content    Markdown内容
     * @param outputPath 输出路径
     */
    @Override
    public void export(String content, String outputPath) {
        FileUtils.writeFileContent(outputPath, content);
        log.debug("Markdown exported to: {}", outputPath);
    }
}
