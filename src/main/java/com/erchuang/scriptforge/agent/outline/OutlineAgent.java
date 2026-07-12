package com.erchuang.scriptforge.agent.outline;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.agent.search.SearchResultStore;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.infra.WorkspaceFileWriter;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.llm.TokenCounter;
import com.erchuang.scriptforge.model.dto.SseEventDTO;
import com.erchuang.scriptforge.model.entity.BranchPoint;
import com.erchuang.scriptforge.model.entity.Outline;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.entity.Requirement;
import com.erchuang.scriptforge.model.enums.ScopeLevel;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import com.erchuang.scriptforge.repository.BranchPointRepository;
import com.erchuang.scriptforge.repository.OutlineRepository;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.RequirementRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大纲设计Agent——生成3版差异化大纲，支持雷同度检测和用户选定.
 *
 * @author ScriptForge Team
 */
@Component
public class OutlineAgent {

    private static final Logger log = LoggerFactory.getLogger(OutlineAgent.class);

    private final ProjectRepository projectRepository;
    private final RequirementRepository requirementRepository;
    private final OutlineRepository outlineRepository;
    private final DeepSeekClient deepSeekClient;
    private final PromptTemplate promptTemplate;
    private final TokenCounter tokenCounter;
    private final SseEmitterService sseEmitterService;
    private final TransactionTemplate transactionTemplate;
    private final WorkspaceFileWriter workspaceFileWriter;
    private final BranchPointRepository branchPointRepository;
    private final ObjectMapper objectMapper;
    private final SearchResultStore searchResultStore;

    public OutlineAgent(ProjectRepository projectRepository,
                         RequirementRepository requirementRepository,
                         OutlineRepository outlineRepository,
                         DeepSeekClient deepSeekClient,
                         PromptTemplate promptTemplate,
                         TokenCounter tokenCounter,
                         SseEmitterService sseEmitterService,
                         TransactionTemplate transactionTemplate,
                         WorkspaceFileWriter workspaceFileWriter,
                         BranchPointRepository branchPointRepository,
                         ObjectMapper objectMapper,
                         SearchResultStore searchResultStore) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.outlineRepository = outlineRepository;
        this.deepSeekClient = deepSeekClient;
        this.promptTemplate = promptTemplate;
        this.tokenCounter = tokenCounter;
        this.sseEmitterService = sseEmitterService;
        this.transactionTemplate = transactionTemplate;
        this.workspaceFileWriter = workspaceFileWriter;
        this.branchPointRepository = branchPointRepository;
        this.objectMapper = objectMapper;
        this.searchResultStore = searchResultStore;
    }

    /**
     * 执行大纲设计——生成3版大纲并保存到数据库.
     *
     * @param projectId 项目ID
     * @return Agent执行结果
     */
    public AgentResult execute(Long projectId) {
        log.info("OutlineAgent started for project {}", projectId);

        try {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

            Requirement requirement = requirementRepository.findByProjectId(projectId).orElse(null);
            if (requirement == null) {
                return AgentResult.failure("请先完成需求调研");
            }

            WritingStyle style = requirement.getStylePreference();
            ScopeLevel scope = requirement.getScopeLevel();

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("OUTLINE", "正在生成第1版大纲...", 15));

            OutlineGenerator generator = new OutlineGenerator(deepSeekClient, promptTemplate, tokenCounter);

            String characterCards = searchResultStore.getCharacterResult(projectId);
            String searchResult = searchResultStore.getSearchResult(projectId);

            List<String> generatedOutlines = generator.generate(
                    projectId,
                    requirement.getSummaryContent(),
                    characterCards != null ? characterCards : "",
                    searchResult != null ? searchResult : "",
                    style, scope);

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("OUTLINE", "正在检查大纲差异化...", 70));

            // 雷同度检测
            OutlineComparator comparator = new OutlineComparator();
            if (comparator.hasDuplicate(generatedOutlines)) {
                log.warn("Generated outlines are too similar, using as-is but marking");
            }

            // 编程式事务：避免 Spring AOP 自调用不经过代理的问题
            List<Outline> savedOutlines = transactionTemplate.execute(status -> {
                outlineRepository.unselectAllByProjectId(projectId);

                List<Outline> result = new java.util.ArrayList<>();
                for (int i = 0; i < generatedOutlines.size(); i++) {
                    String outlineText = generatedOutlines.get(i);
                    String title = extractTitle(outlineText, project.getTitle() + " - 版本" + (i + 1));

                    Outline outline = Outline.builder()
                            .project(project)
                            .versionNumber(i + 1)
                            .title(title)
                            .summary(extractSection(outlineText, "故事梗概"))
                            .coreConflict(extractSection(outlineText, "核心冲突"))
                            .emotionalArc(extractSection(outlineText, "情感走向"))
                            .chapters(extractSection(outlineText, "章节划分"))
                            .selected(i == 0)
                            .build();

                    result.add(outlineRepository.save(outline));
                }
                return result;
            });

            log.info("OutlineAgent completed for project {}, generated {} outlines, auto-selected version 1",
                    projectId, generatedOutlines.size());

            // 解析并保存分支点
            parseAndSaveBranchPoints(project, savedOutlines);

            // 写入工作空间文件
            for (int i = 0; i < generatedOutlines.size(); i++) {
                workspaceFileWriter.write(projectId, "大纲-版本" + (i + 1) + ".md",
                        generatedOutlines.get(i));
            }

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.completed("OUTLINE", "大纲生成完成，共" + generatedOutlines.size() + "版（已自动选中第1版，可后续修改）"));

            return AgentResult.success(String.join("\n\n---\n\n", generatedOutlines),
                    Map.of("outlineCount", generatedOutlines.size()));
        } catch (Exception e) {
            log.error("OutlineAgent failed: {}", e.getMessage(), e);
            return AgentResult.failure("大纲设计失败: " + e.getMessage());
        }
    }

    private String extractTitle(String outlineText, String defaultTitle) {
        if (outlineText == null) return defaultTitle;
        for (String line : outlineText.split("\n")) {
            if (line.trim().startsWith("# ") && !line.trim().startsWith("## ")) {
                return line.trim().substring(2).trim();
            }
        }
        return defaultTitle;
    }

    private String extractSection(String text, String sectionName) {
        if (text == null) return "";
        boolean inSection = false;
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.contains(sectionName)) {
                inSection = true;
                continue;
            }
            if (inSection) {
                if (line.startsWith("#") || line.startsWith("---")) {
                    break;
                }
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 从大纲文本中解析分支点定义并保存到数据库.
     * 格式：
     *   ## 分支点N：触发章节M
     *   **分支标题**：标题文本
     *   **选项A（标签）：**描述 → 后续章节说明
     *   **选项B（标签）：**描述 → 后续章节说明
     */
    private void parseAndSaveBranchPoints(Project project, List<Outline> outlines) {
        // 先删除该项目的旧分支点
        List<BranchPoint> existing = branchPointRepository.findByProjectIdOrderByTriggerChapterAsc(project.getId());
        if (!existing.isEmpty()) {
            branchPointRepository.deleteAll(existing);
        }

        for (Outline outline : outlines) {
            if (!outline.getSelected()) continue; // 只解析选定版本的分支点

            String chaptersText = outline.getChapters();
            if (chaptersText == null || chaptersText.isBlank()) continue;

            Pattern branchHeader = Pattern.compile(
                    "^##\\s*分支点(\\d+)[：:]\\s*触发(?:章节)?(\\d+)",
                    Pattern.CASE_INSENSITIVE
            );
            Pattern branchTitle = Pattern.compile("^\\*\\*分支标题\\*\\*[：:]\\s*(.*)");
            Pattern optionPattern = Pattern.compile("^\\*\\*选项([A-Z]+)[（(]([^）)]+)[）)][：:]\\*\\*(.*?)(?:→|$)(.*)");

            String[] lines = chaptersText.split("\n");
            BranchPoint currentBranch = null;
            StringBuilder optionsJson = new StringBuilder("[");
            int optionCount = 0;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                Matcher bm = branchHeader.matcher(trimmed);
                if (bm.find()) {
                    // 保存前一个分支点
                    if (currentBranch != null) {
                        optionsJson.append("]");
                        currentBranch.setOptions(optionsJson.toString());
                        branchPointRepository.save(currentBranch);
                    }

                    int branchNum = Integer.parseInt(bm.group(1));
                    int triggerCh = Integer.parseInt(bm.group(2));

                    currentBranch = BranchPoint.builder()
                            .project(project)
                            .outline(outline)
                            .triggerChapter(triggerCh)
                            .title("分支点" + branchNum)
                            .status("PENDING")
                            .build();
                    optionsJson = new StringBuilder("[");
                    optionCount = 0;
                    continue;
                }

                if (currentBranch == null) continue;

                Matcher tm = branchTitle.matcher(trimmed);
                if (tm.find()) {
                    currentBranch.setTitle(tm.group(1).trim());
                    continue;
                }

                Matcher om = optionPattern.matcher(trimmed);
                if (om.find()) {
                    if (optionCount > 0) optionsJson.append(",");
                    String label = om.group(1).trim();
                    String tag = om.group(2).trim();
                    String desc = om.group(3).trim();
                    String nextChapters = om.group(4).replace("→", "").trim();

                    Map<String, String> option = new LinkedHashMap<>();
                    option.put("label", label);
                    option.put("tag", tag);
                    option.put("description", desc);
                    option.put("nextChapters", nextChapters);

                    try {
                        optionsJson.append(objectMapper.writeValueAsString(option));
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to serialize branch option: {}", e.getMessage());
                    }
                    optionCount++;
                }
            }

            // 保存最后一个分支点
            if (currentBranch != null) {
                optionsJson.append("]");
                currentBranch.setOptions(optionsJson.toString());
                branchPointRepository.save(currentBranch);
            }

            if (optionCount > 0) {
                log.info("Parsed {} branch points for outline {}", optionCount, outline.getId());
            }
        }
    }
}
