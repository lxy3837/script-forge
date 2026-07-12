package com.erchuang.scriptforge.agent.script;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.agent.search.SearchResultStore;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.infra.WorkspaceFileWriter;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.llm.TokenCounter;
import com.erchuang.scriptforge.model.dto.SseEventDTO;
import com.erchuang.scriptforge.model.entity.*;
import com.erchuang.scriptforge.model.enums.ScopeLevel;
import com.erchuang.scriptforge.model.enums.ScriptStatus;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import com.erchuang.scriptforge.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剧本创作Agent——基于选定大纲逐章生成分镜剧本.
 *
 * @author ScriptForge Team
 */
@Component
public class ScriptAgent {

    private static final Logger log = LoggerFactory.getLogger(ScriptAgent.class);

    private final ProjectRepository projectRepository;
    private final RequirementRepository requirementRepository;
    private final OutlineRepository outlineRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptChapterRepository chapterRepository;
    private final BranchPointRepository branchPointRepository;
    private final SearchResultStore searchResultStore;
    private final DeepSeekClient deepSeekClient;
    private final PromptTemplate promptTemplate;
    private final TokenCounter tokenCounter;
    private final SseEmitterService sseEmitterService;
    private final WorkspaceFileWriter workspaceFileWriter;

    public ScriptAgent(ProjectRepository projectRepository,
                        RequirementRepository requirementRepository,
                        OutlineRepository outlineRepository,
                        ScriptRepository scriptRepository,
                        ScriptChapterRepository chapterRepository,
                        BranchPointRepository branchPointRepository,
                        SearchResultStore searchResultStore,
                        DeepSeekClient deepSeekClient,
                        PromptTemplate promptTemplate,
                        TokenCounter tokenCounter,
                        SseEmitterService sseEmitterService,
                        WorkspaceFileWriter workspaceFileWriter) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.outlineRepository = outlineRepository;
        this.scriptRepository = scriptRepository;
        this.chapterRepository = chapterRepository;
        this.branchPointRepository = branchPointRepository;
        this.searchResultStore = searchResultStore;
        this.deepSeekClient = deepSeekClient;
        this.promptTemplate = promptTemplate;
        this.tokenCounter = tokenCounter;
        this.sseEmitterService = sseEmitterService;
        this.workspaceFileWriter = workspaceFileWriter;
    }

    /**
     * 执行剧本生成.
     *
     * @param projectId 项目ID
     * @return Agent执行结果
     */
    public AgentResult execute(Long projectId) {
        System.out.println(">>> ScriptAgent.execute() called for project " + projectId + " at " + java.time.LocalDateTime.now());
        log.info("ScriptAgent started for project {}", projectId);

        try {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

            // 获取选定的大纲
            Outline selectedOutline = outlineRepository.findByProjectIdAndSelectedTrue(projectId)
                    .orElseThrow(() -> new RuntimeException("请先选定大纲"));

            // 获取需求和风格
            Requirement requirement = requirementRepository.findByProjectId(projectId).orElse(null);
            WritingStyle style = requirement != null ? requirement.getStylePreference() : WritingStyle.LIGHT_NOVEL;
            ScopeLevel scope = requirement != null ? requirement.getScopeLevel() : ScopeLevel.MEDIUM;

            // 检查已有剧本（支持断点续传和分支后继续）
            Script script;
            List<ScriptChapter> existingChapters;
            int startChapter = 1;
            String previousContent = "";

            List<Script> scripts = scriptRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
            if (!scripts.isEmpty() && scripts.get(0).getTotalChapters() != null && scripts.get(0).getTotalChapters() > 0) {
                // 复用已有剧本
                script = scripts.get(0);
                existingChapters = chapterRepository.findByScriptIdOrderByChapterNumberAsc(script.getId());
                startChapter = existingChapters.size() + 1;

                // 构建已有章节的上下文
                StringBuilder fullPrev = new StringBuilder();
                for (ScriptChapter ch : existingChapters) {
                    fullPrev.append("## 第").append(ch.getChapterNumber()).append("章 ")
                            .append(ch.getTitle()).append("\n\n");
                    if (ch.getRawContent() != null) fullPrev.append(ch.getRawContent()).append("\n\n");
                }
                previousContent = fullPrev.toString();

                log.info("Resuming ScriptAgent for project {}, existing {} chapters, starting from chapter {}",
                        projectId, existingChapters.size(), startChapter);

                if (startChapter > script.getTotalChapters()) {
                    // 所有章节已生成完毕
                    StringBuilder fullScript = new StringBuilder("# " + script.getTitle() + "\n\n");
                    fullScript.append(previousContent);
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("scriptId", script.getId());
                    metadata.put("totalChapters", existingChapters.size());
                    metadata.put("fullScript", fullScript.toString());
                    return AgentResult.success(fullScript.toString(), metadata);
                }
            } else {
                // 创建新剧本记录
                script = Script.builder()
                        .project(project)
                        .outline(selectedOutline)
                        .title(project.getTitle() + " - 剧本")
                        .writingStyle(style)
                        .status(ScriptStatus.DRAFT)
                        .totalChapters(0)
                        .build();
                script = scriptRepository.save(script);
                existingChapters = List.of();
            }

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("SCRIPT", "正在逐章生成剧本...", 20));

            // 获取人设/搜索结果作为上下文
            String characterCards = searchResultStore.getCharacterResult(projectId);
            if (characterCards == null) characterCards = "";
            String searchResult = searchResultStore.getSearchResult(projectId);
            if (searchResult == null) searchResult = "";

            // 生成剧本
            ChapterGenerator chapterGenerator = new ChapterGenerator(deepSeekClient, promptTemplate, tokenCounter);

            // 构造大纲完整上下文
            String fullOutlineContext = buildOutlineContext(selectedOutline);

            // 从大纲中解析章节摘要和标题
            String outlineChapters = selectedOutline.getChapters();
            var parsed = parseChapterSummaries(outlineChapters);
            Map<Integer, String> chapterSummaries = parsed.summaries();
            Map<Integer, String> chapterTitles = parsed.titles();
            int totalChapters = parsed.count();
            // 如果大纲解析出的章节数为0，fallback用 scope 设定的值
            if (totalChapters == 0) {
                totalChapters = scope.getMinChapters();
                log.warn("大纲解析出的章节数为0，fallback用 scope 的 {} 章", totalChapters);
            }

            // 只在首次创建时设置总章数
            if (existingChapters.isEmpty()) {
                script.setTotalChapters(totalChapters);
                scriptRepository.save(script);
            } else {
                totalChapters = script.getTotalChapters();
            }

            // 获取所有分支点（按触发章节排序）
            List<BranchPoint> branchPoints = branchPointRepository
                    .findByProjectIdOrderByTriggerChapterAsc(projectId);

            StringBuilder fullScript = new StringBuilder("# " + script.getTitle() + "\n\n");
            if (!previousContent.isEmpty()) {
                fullScript.append(previousContent);
            }
            int generatedCount = existingChapters.size();
            Long activeBranchId = null;

            for (int i = startChapter; i <= totalChapters; i++) {
                // 检查当前章节是否为待处理分支点的触发章节
                BranchPoint pendingBranch = findPendingBranch(branchPoints, i);
                if (pendingBranch != null && !"SELECTED".equals(pendingBranch.getStatus())) {
                    log.info("Branch point pending at chapter {} for project {}", i, projectId);
                    // 发送分支选择SSE事件
                    sseEmitterService.sendProgress(projectId,
                            SseEventDTO.running("BRANCH",
                                    "分支点：" + pendingBranch.getTitle(),
                                    (int)(20 + (60.0 * i / totalChapters))));
                    // 跳过后续章节，标记分支待处理
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("scriptId", script.getId());
                    metadata.put("totalChapters", generatedCount);
                    metadata.put("fullScript", fullScript.toString());
                    metadata.put("branchPending", true);
                    metadata.put("branchPointId", pendingBranch.getId());
                    metadata.put("branchAtChapter", i);
                    metadata.put("branchTitle", pendingBranch.getTitle());
                    metadata.put("branchOptions", pendingBranch.getOptions());
                    metadata.put("savedChapterCount", generatedCount);
                    return AgentResult.success(fullScript.toString(), metadata);
                }

                // 如果之前选择了分支，使用已选择的分支ID
                if (activeBranchId == null && pendingBranch != null
                        && "SELECTED".equals(pendingBranch.getStatus())) {
                    activeBranchId = pendingBranch.getId();
                }
                // 从大纲解析的章节标题，找不到则用兜底
                String chapterTitle = chapterTitles.getOrDefault(i, "第" + i + "章");

                // 从大纲解析的章节摘要，找不到则用大纲全文作为兜底
                String chapterAbstract = chapterSummaries.getOrDefault(i, fullOutlineContext);
                if (chapterAbstract == null || chapterAbstract.isBlank()) {
                    chapterAbstract = fullOutlineContext;
                }

                sseEmitterService.sendProgress(projectId,
                        SseEventDTO.running("SCRIPT",
                                "正在生成第" + i + "/" + totalChapters + "章...",
                                20 + (60 * i / totalChapters)));

                ChapterGenerator.ChapterResult chapterResult = null;
                try {
                    chapterResult = chapterGenerator.generateChapter(
                            i, chapterTitle, chapterAbstract, fullOutlineContext,
                            previousContent, characterCards, searchResult, style,
                            projectId);
                } catch (Exception chapterEx) {
                    log.error("Chapter {} generation failed, skipping: {}", i, chapterEx.getMessage());
                    sseEmitterService.sendProgress(projectId,
                            SseEventDTO.running("SCRIPT",
                                    "第" + i + "章生成失败，跳过继续...",
                                    20 + (60 * i / totalChapters)));
                    continue;
                }

                if (chapterResult != null) {
                    // 保存章节到数据库
                    ScriptChapter chapter = ScriptChapter.builder()
                            .script(script)
                            .chapterNumber(i)
                            .title(chapterResult.title())
                            .rawContent(chapterResult.rawContent())
                            .reasoning(chapterResult.reasoning())
                            .sceneCount(chapterResult.sceneCount())
                            .build();
                    chapterRepository.save(chapter);

                    // 写入纯剧本到工作空间文件
                    workspaceFileWriter.write(projectId,
                            "chapters/" + String.format("%02d", i) + "-" + sanitizeFileName(chapterResult.title()) + ".md",
                            "# " + chapterResult.title() + "\n\n" + chapterResult.rawContent());

                    // 独立保存AI思考过程
                    if (chapterResult.reasoning() != null && !chapterResult.reasoning().isBlank()) {
                        workspaceFileWriter.write(projectId,
                                "chapters/reasoning/" + String.format("%02d", i) + "-reasoning.md",
                                "# 第" + i + "章 AI思考过程\n\n" + chapterResult.reasoning());
                    }

                    fullScript.append("## ").append(chapterResult.title()).append("\n\n");
                    fullScript.append(chapterResult.rawContent()).append("\n\n");
                    previousContent = chapterResult.rawContent();
                    generatedCount++;
                }
            }

            // 更新剧本
            script.setTotalChapters(generatedCount);
            script.setStatus(ScriptStatus.DRAFT);
            scriptRepository.save(script);

            log.info("ScriptAgent completed for project {}, generated {} chapters", projectId, generatedCount);

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.completed("SCRIPT", "剧本生成完成，共" + generatedCount + "章"));

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("scriptId", script.getId());
            metadata.put("totalChapters", generatedCount);
            metadata.put("fullScript", fullScript.toString());

            return AgentResult.success(fullScript.toString(), metadata);
        } catch (Exception e) {
            log.error("ScriptAgent failed: {}", e.getMessage(), e);
            return AgentResult.failure("剧本创作失败: " + e.getMessage());
        }
    }

    /**
     * 解析大纲章节摘要的结果.
     */
    private record ParsedChapters(
            Map<Integer, String> summaries,  // chapterNum -> 摘要
            Map<Integer, String> titles,     // chapterNum -> 标题
            int count                         // 解析到的章节数
    ) {}

    /**
     * 从大纲的 chapters 文本中解析各章摘要与标题.
     * 支持格式：
     *   ### 第X章：标题 / ### 第X章: 标题   (Markdown heading)
     *   第X章：标题 / 第X章: 标题            (plain text)
     *   **第X章 标题**                        (bold)
     *   X. 标题                               (numbered)
     * 标题后的文字（直到下一章或空行分隔符）为摘要内容.
     */
    private ParsedChapters parseChapterSummaries(String chaptersText) {
        Map<Integer, String> summaries = new LinkedHashMap<>();
        Map<Integer, String> titles = new LinkedHashMap<>();

        if (chaptersText == null || chaptersText.isBlank()) {
            return new ParsedChapters(summaries, titles, 0);
        }

        // 支持多种格式的章节标题正则
        Pattern chapterHeader = Pattern.compile(
                "(?:#{1,4}\\s*|\\*\\*)?第\\s*(\\d+)\\s*章\\s*[：:：]?\\s*(.*?)(?:\\*\\*)?\\s*$"
        );
        // 备用格式：纯数字编号 "1. 标题" 或 "1、标题"
        Pattern numberedHeader = Pattern.compile("^(\\d+)[.、．]\\s*(.*)$");

        String[] lines = chaptersText.split("\n");
        int currentChapter = -1;
        StringBuilder currentSummary = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 跳过纯分隔线和不相关的标题
            if (trimmed.equals("---") || trimmed.equals("***")) continue;
            if (trimmed.matches("^#{1,4}\\s*(章节划分|章节规划|大纲|目录|剧情大纲).*")) continue;

            // 尝试匹配 "第X章：标题"
            Matcher m = chapterHeader.matcher(trimmed);
            boolean matched = false;
            if (m.find()) {
                int chNum = Integer.parseInt(m.group(1));
                String titlePart = m.group(2).trim();
                // 保存上一章
                savePreviousChapter(summaries, titles, currentChapter, currentSummary, titlePart);
                currentChapter = chNum;
                // 如果标题只包含章节号没有标题文本，用兜底
                titles.put(chNum, titlePart.isEmpty() ? "第" + chNum + "章" : titlePart);
                currentSummary = new StringBuilder();
                matched = true;
            }

            // 如果没匹配到 "第X章" 格式，尝试编号格式
            if (!matched) {
                Matcher nm = numberedHeader.matcher(trimmed);
                if (nm.find()) {
                    int chNum = Integer.parseInt(nm.group(1));
                    String titlePart = nm.group(2).trim();
                    if (currentChapter > 0 && currentSummary.length() > 0) {
                        summaries.put(currentChapter, currentSummary.toString().trim());
                    }
                    currentChapter = chNum;
                    titles.put(chNum, titlePart.isEmpty() ? "第" + chNum + "章" : titlePart);
                    currentSummary = new StringBuilder();
                    matched = true;
                }
            }

            // 如果没匹配到章节标题，当前行属于当前章的摘要内容
            if (!matched && currentChapter > 0) {
                // 跳过 Markdown 标题行和粗体标记行
                if (!trimmed.startsWith("#") && !trimmed.startsWith("**")) {
                    currentSummary.append(trimmed).append("\n");
                }
            }
        }

        // 最后一章
        if (currentChapter > 0 && currentSummary.length() > 0) {
            summaries.put(currentChapter, currentSummary.toString().trim());
        }

        int count = summaries.size();
        if (count == 0) {
            // 最后兜底：把第一个 matches 到的标题也算上
            count = titles.size();
            // 如果连标题都没有，尝试全文按 scope 预设值切分
            if (count == 0) {
                count = 5; // 默认5章，由调用方 fallback 到 scope
            }
        }

        return new ParsedChapters(summaries, titles, count);
    }

    private void savePreviousChapter(Map<Integer, String> summaries, Map<Integer, String> titles,
                                      int chNum, StringBuilder summary, String title) {
        if (chNum > 0 && summary.length() > 0) {
            summaries.put(chNum, summary.toString().trim());
        }
    }

    /**
     * 构建大纲完整上下文（故事梗概 + 核心冲突 + 情感走向 + 章节划分）
     */
    private String buildOutlineContext(Outline outline) {
        StringBuilder sb = new StringBuilder();
        if (outline.getSummary() != null && !outline.getSummary().isBlank()) {
            sb.append("## 故事梗概\n").append(outline.getSummary()).append("\n\n");
        }
        if (outline.getCoreConflict() != null && !outline.getCoreConflict().isBlank()) {
            sb.append("## 核心冲突\n").append(outline.getCoreConflict()).append("\n\n");
        }
        if (outline.getEmotionalArc() != null && !outline.getEmotionalArc().isBlank()) {
            sb.append("## 情感走向\n").append(outline.getEmotionalArc()).append("\n\n");
        }
        if (outline.getChapters() != null && !outline.getChapters().isBlank()) {
            sb.append("## 章节划分\n").append(outline.getChapters());
        }
        return sb.toString().trim();
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return "untitled";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "-");
    }

    /**
     * 在分支点列表中查找匹配指定触发章节的待处理分支点.
     */
    private BranchPoint findPendingBranch(List<BranchPoint> branchPoints, int chapterNumber) {
        if (branchPoints == null || branchPoints.isEmpty()) return null;
        for (BranchPoint bp : branchPoints) {
            if (bp.getTriggerChapter() != null && bp.getTriggerChapter() == chapterNumber) {
                return bp;
            }
        }
        return null;
    }
}
