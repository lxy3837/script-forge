package com.erchuang.scriptforge.agent.review;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.infra.WorkspaceFileWriter;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.EmbeddingService;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.model.dto.SseEventDTO;
import com.erchuang.scriptforge.model.entity.ReviewReport;
import com.erchuang.scriptforge.model.entity.Script;
import com.erchuang.scriptforge.model.entity.ScriptChapter;
import com.erchuang.scriptforge.repository.CharacterCardRepository;
import com.erchuang.scriptforge.repository.ReviewReportRepository;
import com.erchuang.scriptforge.repository.ScriptChapterRepository;
import com.erchuang.scriptforge.repository.ScriptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 质量审核Agent——多维度自动审核剧本质量.
 *
 * @author ScriptForge Team
 */
@Component
public class ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);

    private final ScriptRepository scriptRepository;
    private final ScriptChapterRepository chapterRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final DeepSeekClient deepSeekClient;
    private final PromptTemplate promptTemplate;
    private final EmbeddingService embeddingService;
    private final CharacterCardRepository characterCardRepository;
    private final ObjectMapper objectMapper;
    private final SseEmitterService sseEmitterService;
    private final WorkspaceFileWriter workspaceFileWriter;

    public ReviewAgent(ScriptRepository scriptRepository,
                        ScriptChapterRepository chapterRepository,
                        ReviewReportRepository reviewReportRepository,
                        DeepSeekClient deepSeekClient,
                        PromptTemplate promptTemplate,
                        EmbeddingService embeddingService,
                        CharacterCardRepository characterCardRepository,
                        ObjectMapper objectMapper,
                        SseEmitterService sseEmitterService,
                        WorkspaceFileWriter workspaceFileWriter) {
        this.scriptRepository = scriptRepository;
        this.chapterRepository = chapterRepository;
        this.reviewReportRepository = reviewReportRepository;
        this.deepSeekClient = deepSeekClient;
        this.promptTemplate = promptTemplate;
        this.embeddingService = embeddingService;
        this.characterCardRepository = characterCardRepository;
        this.objectMapper = objectMapper;
        this.sseEmitterService = sseEmitterService;
        this.workspaceFileWriter = workspaceFileWriter;
    }

    /**
     * 执行质量审核.
     *
     * @param projectId 项目ID
     * @return Agent执行结果
     */
    public AgentResult execute(Long projectId) {
        log.info("ReviewAgent started for project {}", projectId);

        try {
            // 获取最新剧本
            List<Script> scripts = scriptRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
            if (scripts.isEmpty()) {
                return AgentResult.failure("没有可审核的剧本");
            }
            Script script = scripts.get(0);

            // 获取所有章节
            List<ScriptChapter> chapters = chapterRepository.findByScriptIdOrderByChapterNumberAsc(script.getId());
            String fullScript = buildFullScript(chapters);

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("REVIEW", "正在进行OOC检测（向量相似度）...", 15));

            // 主检测：基于向量相似度的本地OOC检测（不消耗LLM Token）
            OOCVectorDetector vectorDetector = new OOCVectorDetector(
                    embeddingService, characterCardRepository, objectMapper);
            String oocResult = vectorDetector.detect(fullScript, "");

            // 如果向量检测未发现问题，且角色人设数据充分，用LLM做补充审核
            if ((oocResult == null || oocResult.contains("\"totalIssues\": 0"))
                    && fullScript.length() > 500) {
                log.info("Vector OOC found no issues, running LLM-based OOC as supplement for project {}", projectId);
                sseEmitterService.sendProgress(projectId,
                        SseEventDTO.running("REVIEW", "正在进行OOC深度检测（LLM辅助）...", 25));
                OOCDetector llmDetector = new OOCDetector(deepSeekClient, promptTemplate);
                String llmResult = llmDetector.detect(fullScript, "");
                if (llmResult != null && !llmResult.contains("执行异常")) {
                    oocResult = llmResult;
                }
            }

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("REVIEW", "正在进行逻辑一致性检查...", 40));

            LogicChecker logicChecker = new LogicChecker(deepSeekClient, promptTemplate);
            String logicResult = logicChecker.check(fullScript);

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("REVIEW", "正在进行节奏评估...", 65));

            PacingAnalyzer pacingAnalyzer = new PacingAnalyzer(deepSeekClient, promptTemplate);
            String pacingResult = pacingAnalyzer.analyze(fullScript, chapters.size());

            // 计算综合评分
            BigDecimal score = calculateOverallScore(oocResult, logicResult, pacingResult);

            // 构建审核报告
            ReviewReportBuilder reportBuilder = new ReviewReportBuilder();
            String report = reportBuilder.build(oocResult, logicResult, pacingResult, score);

            // 保存到数据库
            ReviewReport reviewReport = new ReviewReport();
            reviewReport.setProject(script.getProject());
            reviewReport.setScript(script);
            reviewReport.setOocIssues(oocResult);
            reviewReport.setLogicIssues(logicResult);
            reviewReport.setPacingAnalysis(pacingResult);
            reviewReport.setOverallScore(score);
            reviewReport.setStatus("PENDING");
            reviewReportRepository.save(reviewReport);

            // 写入工作空间文件
            workspaceFileWriter.write(projectId, "审核报告.md", report);

            log.info("ReviewAgent completed for project {}, score: {}", projectId, score);

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.completed("REVIEW", "审核完成，综合评分: " + score));

            return AgentResult.success(report, Map.of("score", score, "reportId", reviewReport.getId()));
        } catch (Exception e) {
            log.error("ReviewAgent failed: {}", e.getMessage(), e);
            return AgentResult.failure("质量审核失败: " + e.getMessage());
        }
    }

    private String buildFullScript(List<ScriptChapter> chapters) {
        StringBuilder sb = new StringBuilder();
        for (ScriptChapter chapter : chapters) {
            sb.append("## 第").append(chapter.getChapterNumber()).append("章 ")
                    .append(chapter.getTitle()).append("\n\n");
            if (chapter.getRawContent() != null) {
                sb.append(chapter.getRawContent()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private BigDecimal calculateOverallScore(String oocResult, String logicResult, String pacingResult) {
        // 简化评分逻辑：基于三个维度的结果文本长度近似评分
        double baseScore = 75.0;
        if (oocResult != null && oocResult.contains("严重")) baseScore -= 15;
        else if (oocResult != null && oocResult.contains("问题")) baseScore -= 5;

        if (logicResult != null && logicResult.contains("严重矛盾")) baseScore -= 10;
        else if (logicResult != null && logicResult.contains("逻辑")) baseScore -= 3;

        if (pacingResult != null && pacingResult.contains("节奏问题")) baseScore -= 8;

        return BigDecimal.valueOf(Math.max(10.0, Math.min(100.0, baseScore)))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
