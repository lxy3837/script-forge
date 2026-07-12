package com.erchuang.scriptforge.agent.document;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.export.ExportEngine;
import com.erchuang.scriptforge.infra.FileUtils;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.infra.WorkspaceFileWriter;
import com.erchuang.scriptforge.model.dto.SseEventDTO;
import com.erchuang.scriptforge.model.entity.*;
import com.erchuang.scriptforge.model.enums.ExportFormat;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.ScriptChapterRepository;
import com.erchuang.scriptforge.repository.ScriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档编辑Agent——多格式导出、增量修改、版本管理（简要实现）.
 * <p>
 * 导出委托给export/包进行，本Agent主要负责编排和数据库操作。
 * </p>
 *
 * @author ScriptForge Team
 */
@Component
public class DocumentAgent {

    private static final Logger log = LoggerFactory.getLogger(DocumentAgent.class);

    private final ProjectRepository projectRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptChapterRepository chapterRepository;
    private final ExportEngine exportEngine;
    private final SseEmitterService sseEmitterService;
    private final WorkspaceFileWriter workspaceFileWriter;

    @Value("${app.export-dir}")
    private String exportDir;

    public DocumentAgent(ProjectRepository projectRepository,
                          ScriptRepository scriptRepository,
                          ScriptChapterRepository chapterRepository,
                          ExportEngine exportEngine,
                          SseEmitterService sseEmitterService,
                          WorkspaceFileWriter workspaceFileWriter) {
        this.projectRepository = projectRepository;
        this.scriptRepository = scriptRepository;
        this.chapterRepository = chapterRepository;
        this.exportEngine = exportEngine;
        this.sseEmitterService = sseEmitterService;
        this.workspaceFileWriter = workspaceFileWriter;
    }

    /**
     * 执行多格式导出.
     *
     * @param projectId 项目ID
     * @return Agent执行结果
     */
    public AgentResult exportDocument(Long projectId) {
        return exportDocument(projectId, ExportFormat.MARKDOWN);
    }

    /**
     * 执行指定格式导出.
     *
     * @param projectId 项目ID
     * @param format    导出格式
     * @return Agent执行结果
     */
    public AgentResult exportDocument(Long projectId, ExportFormat format) {
        log.info("DocumentAgent exporting project {} to {}", projectId, format.getDisplayName());

        try {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("EXPORT", "正在导出" + format.getDisplayName() + "...", 20));

            // 获取最新剧本
            List<Script> scripts = scriptRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
            if (scripts.isEmpty()) {
                return AgentResult.failure("没有可导出的剧本");
            }
            Script script = scripts.get(0);

            // 获取所有章节并拼接
            List<ScriptChapter> chapters = chapterRepository.findByScriptIdOrderByChapterNumberAsc(script.getId());
            StringBuilder fullContent = new StringBuilder();
            fullContent.append("# ").append(project.getTitle()).append("\n\n");
            for (ScriptChapter chapter : chapters) {
                fullContent.append("## 第").append(chapter.getChapterNumber()).append("章 ")
                        .append(chapter.getTitle()).append("\n\n");
                if (chapter.getRawContent() != null) {
                    fullContent.append(chapter.getRawContent()).append("\n\n");
                }
            }

            // 生成输出路径
            FileUtils.ensureDirectoryExists(exportDir);
            String safeFileName = FileUtils.sanitizeFileName(project.getTitle());
            String outputPath = exportDir + "/" + safeFileName + format.getExtension();

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("EXPORT", "正在生成文件...", 60));

            // 调用导出引擎
            String resultPath = exportEngine.export(fullContent.toString(), format, outputPath);

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.completed("EXPORT", "导出完成: " + resultPath));

            // 同时写入工作空间
            workspaceFileWriter.write(projectId, "最终剧本.md", fullContent.toString());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("exportPath", resultPath);
            metadata.put("format", format.name());

            log.info("DocumentAgent export completed: {}", resultPath);
            return AgentResult.success("文件已导出至: " + resultPath, metadata);
        } catch (Exception e) {
            log.error("DocumentAgent export failed: {}", e.getMessage(), e);
            return AgentResult.failure("导出失败: " + e.getMessage());
        }
    }
}
