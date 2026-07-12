package com.erchuang.scriptforge.agent.orchestrator;

import com.erchuang.scriptforge.agent.character.CharacterRetrievalAgent;
import com.erchuang.scriptforge.agent.document.DocumentAgent;
import com.erchuang.scriptforge.agent.outline.OutlineAgent;
import com.erchuang.scriptforge.agent.question.QuestionAgent;
import com.erchuang.scriptforge.agent.question.QuestionData.QuestionOption;
import com.erchuang.scriptforge.agent.requirement.RequirementAgent;
import com.erchuang.scriptforge.agent.search.RealtimeSearchAgent;
import com.erchuang.scriptforge.agent.search.SearchResultStore;
import com.erchuang.scriptforge.agent.review.ReviewAgent;
import com.erchuang.scriptforge.agent.script.ScriptAgent;
import com.erchuang.scriptforge.infra.RetryTemplate;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.model.dto.SseEventDTO;
import com.erchuang.scriptforge.model.entity.BranchPoint;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.model.enums.WorkflowStep;
import com.erchuang.scriptforge.repository.BranchPointRepository;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.RequirementRepository;
import com.erchuang.scriptforge.repository.OutlineRepository;
import com.erchuang.scriptforge.stream.StreamTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作流编排器——总调度Agent，按DAG调度各Agent执行.
 * <p>
 * 工作流步骤:
 * REQUIREMENT_GATHERING -> SEARCH_AND_CHARACTER -> OUTLINE_DESIGN
 * -> SCRIPT_GENERATION -> QUALITY_REVIEW -> EXPORT -> DONE
 * </p>
 *
 * @author ScriptForge Team
 */
@Component
public class WorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOrchestrator.class);

    private final ProjectRepository projectRepository;
    private final ProjectStateManager stateManager;
    private final SseEmitterService sseEmitterService;

    private final RequirementAgent requirementAgent;
    private final CharacterRetrievalAgent characterRetrievalAgent;
    private final OutlineAgent outlineAgent;
    private final ScriptAgent scriptAgent;
    private final ReviewAgent reviewAgent;
    private final DocumentAgent documentAgent;
    private final QuestionAgent questionAgent;
    private final RealtimeSearchAgent searchAgent;
    private final SearchResultStore searchResultStore;
    private final RequirementRepository requirementRepository;
    private final OutlineRepository outlineRepository;
    private final BranchPointRepository branchPointRepository;
    private final ObjectMapper objectMapper;

    /** Agent执行线程池 */
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    /** 工作流取消标志（每个项目一个） */
    private final ConcurrentHashMap<Long, AtomicBoolean> cancelRequestMap = new ConcurrentHashMap<>();

    /** 工作流运行中标记（防止并行执行同一项目的工作流） */
    private final ConcurrentHashMap<Long, Boolean> runningWorkflows = new ConcurrentHashMap<>();

    public WorkflowOrchestrator(ProjectRepository projectRepository,
                                 ProjectStateManager stateManager,
                                 SseEmitterService sseEmitterService,
                                 RequirementAgent requirementAgent,
                                 CharacterRetrievalAgent characterRetrievalAgent,
                                 OutlineAgent outlineAgent,
                                 ScriptAgent scriptAgent,
                                 ReviewAgent reviewAgent,
                                 DocumentAgent documentAgent,
                                 QuestionAgent questionAgent,
                                 RealtimeSearchAgent searchAgent,
                                 SearchResultStore searchResultStore,
                                 RequirementRepository requirementRepository,
                                 OutlineRepository outlineRepository,
                                 BranchPointRepository branchPointRepository,
                                 ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.stateManager = stateManager;
        this.sseEmitterService = sseEmitterService;
        this.requirementAgent = requirementAgent;
        this.characterRetrievalAgent = characterRetrievalAgent;
        this.outlineAgent = outlineAgent;
        this.scriptAgent = scriptAgent;
        this.reviewAgent = reviewAgent;
        this.documentAgent = documentAgent;
        this.questionAgent = questionAgent;
        this.searchAgent = searchAgent;
        this.searchResultStore = searchResultStore;
        this.requirementRepository = requirementRepository;
        this.outlineRepository = outlineRepository;
        this.branchPointRepository = branchPointRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动或恢复项目工作流.
     * 如果项目已有部分结果（需求/人设/大纲），则自动跳过已完成步骤.
     *
     * @param projectId 项目ID
     */
    public void startWorkflow(Long projectId) {
        // 防重入：同一项目同时只能有一个工作流运行
        if (runningWorkflows.putIfAbsent(projectId, true) != null) {
            log.warn("Workflow already running for project {}, rejecting duplicate start", projectId);
            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.failed("WORKFLOW", "工作流已在运行中，请等待当前执行完成"));
            return;
        }

        WorkflowStep currentStep = stateManager.getCurrentStep(projectId);
        log.info("Starting/Restoring workflow for project {}, saved step: {}", projectId, currentStep.getDisplayName());

        // 自动跳过已有数据的步骤，找到实际需要执行的起始步骤
        WorkflowStep actualStart = resolveActualStartStep(projectId, currentStep);
        log.info("Resolved actual start step for project {}: {}", projectId, actualStart.getDisplayName());

        // 从实际起始步骤继续执行
        try {
            executeFromStep(projectId, actualStart);
        } finally {
            runningWorkflows.remove(projectId);
        }
    }

    /**
     * 根据数据库中的已有数据，跳过已完成步骤，找到实际需要执行的起始步骤.
     */
    private WorkflowStep resolveActualStartStep(Long projectId, WorkflowStep savedStep) {
        if (savedStep == WorkflowStep.DONE) {
            return WorkflowStep.DONE;
        }
        if (savedStep == WorkflowStep.INIT) {
            return WorkflowStep.REQUIREMENT_GATHERING;
        }

        // 需求已保存 → 跳过 REQUIREMENT_GATHERING
        boolean hasRequirement = requirementRepository.findByProjectId(projectId).isPresent();
        // 人设 + 搜索已保存 → 跳过 SEARCH_AND_CHARACTER
        boolean hasSearchCharacter = searchResultStore.getSearchResult(projectId) != null
                || searchResultStore.getCharacterResult(projectId) != null;
        // 大纲已保存 → 跳过 OUTLINE_DESIGN
        boolean hasOutlines = !outlineRepository.findByProjectIdOrderByVersionNumberAsc(projectId).isEmpty();

        // 按步骤顺序判断
        if (savedStep == WorkflowStep.REQUIREMENT_GATHERING && hasRequirement) {
            return hasSearchCharacter ? WorkflowStep.OUTLINE_DESIGN : WorkflowStep.SEARCH_AND_CHARACTER;
        }
        if (savedStep == WorkflowStep.SEARCH_AND_CHARACTER && hasSearchCharacter) {
            return hasOutlines ? WorkflowStep.SCRIPT_GENERATION : WorkflowStep.OUTLINE_DESIGN;
        }

        return savedStep;
    }

    /**
     * 取消指定项目的工作流.
     *
     * @param projectId 项目ID
     */
    public void cancelWorkflow(Long projectId) {
        AtomicBoolean flag = cancelRequestMap.computeIfAbsent(projectId, k -> new AtomicBoolean(false));
        flag.set(true);
        log.info("Cancel requested for project {}", projectId);
    }

    /**
     * 检查是否已取消.
     */
    private boolean isCancelled(Long projectId) {
        AtomicBoolean flag = cancelRequestMap.get(projectId);
        return flag != null && flag.get();
    }

    /**
     * 重置取消标志（恢复或重新开始时调用）.
     */
    private void resetCancelFlag(Long projectId) {
        cancelRequestMap.remove(projectId);
    }

    /**
     * 从指定步骤开始执行工作流.
     *
     * @param projectId 项目ID
     * @param startStep 起始步骤
     */
    private void executeFromStep(Long projectId, WorkflowStep startStep) {
        resetCancelFlag(projectId);
        WorkflowStep step = startStep;

        while (step != null && step != WorkflowStep.DONE) {
            if (isCancelled(projectId)) {
                log.info("Workflow for project {} cancelled at step {}", projectId, step);
                stateManager.markPaused(projectId, step.getCode());
                StreamTracker.endStep(projectId, step.getCode(), "cancelled", 0);
                sseEmitterService.sendProgress(projectId,
                        SseEventDTO.completed("CANCELLED", "工作流已暂停（当前步骤: " + step.getDisplayName() + "）"));
                return;
            }

            // 发送步骤开始事件
            String stepTitle = getStepTitle(step);
            StreamTracker.startStep(projectId, step.getCode(), stepTitle);
            sseEmitterService.sendProgress(projectId, SseEventDTO.running(step.getCode(), "开始执行: " + stepTitle, getStepProgress(step)));

            try {
                WorkflowStep nextStep;
                switch (step) {
                    case INIT:
                    case REQUIREMENT_GATHERING:
                        nextStep = executeRequirementGathering(projectId);
                        break;
                    case SEARCH_AND_CHARACTER:
                        nextStep = executeSearchAndCharacter(projectId);
                        break;
                    case OUTLINE_DESIGN:
                        nextStep = executeOutlineDesign(projectId);
                        break;
                    case SCRIPT_GENERATION:
                        nextStep = executeScriptGeneration(projectId);
                        break;
                    case QUALITY_REVIEW:
                        nextStep = executeQualityReview(projectId);
                        break;
                    case EXPORT:
                        nextStep = executeExport(projectId);
                        break;
                    default:
                        nextStep = WorkflowStep.DONE;
                }

                // 发送步骤完成事件
                StreamTracker.endStep(projectId, step.getCode(), "completed", 100);
                sseEmitterService.sendProgress(projectId, SseEventDTO.completed(step.getCode(), "已完成: " + stepTitle));
                step = nextStep;

            } catch (Exception e) {
                log.error("Workflow step {} failed for project {}: {}", step, projectId, e.getMessage(), e);
                // 发送步骤失败事件
                StreamTracker.endStep(projectId, step.getCode(), "failed", 0);
                sseEmitterService.sendError(projectId, "步骤 " + step.getDisplayName() + " 失败: " + e.getMessage());
                break;
            }
        }

        if (step == WorkflowStep.DONE) {
            stateManager.markCompleted(projectId);
            sseEmitterService.sendComplete(projectId, "项目工作流已完成");
            log.info("Workflow completed for project {}", projectId);
        }
    }

    private String getStepTitle(WorkflowStep step) {
        return switch (step) {
            case INIT -> "初始化";
            case REQUIREMENT_GATHERING -> "需求调研";
            case SEARCH_AND_CHARACTER -> "信息检索与人物设定";
            case OUTLINE_DESIGN -> "大纲设计";
            case SCRIPT_GENERATION -> "剧本生成";
            case QUALITY_REVIEW -> "质量审核";
            case EXPORT -> "文档导出";
            case DONE -> "完成";
        };
    }

    private int getStepProgress(WorkflowStep step) {
        return switch (step) {
            case INIT -> 0;
            case REQUIREMENT_GATHERING -> 10;
            case SEARCH_AND_CHARACTER -> 25;
            case OUTLINE_DESIGN -> 40;
            case SCRIPT_GENERATION -> 70;
            case QUALITY_REVIEW -> 85;
            case EXPORT -> 95;
            case DONE -> 100;
        };
    }

    private WorkflowStep executeRequirementGathering(Long projectId) {
        log.info("Executing REQUIREMENT_GATHERING for project {}", projectId);
        stateManager.advanceWorkflow(projectId, WorkflowStep.REQUIREMENT_GATHERING);

        AgentResult result = RetryTemplate.execute(
                () -> requirementAgent.execute(projectId),
                "需求调研Agent"
        );

        if (!result.isSuccess()) {
            throw new RuntimeException("需求调研失败: " + result.getErrorMessage());
        }

        // 确认循环：用户可能要求重新分析或补充修改
        while (true) {
            var confirmOptions = java.util.List.of(
                    QuestionOption.of("确认无误，继续下一步", "分析符合预期，直接进入信息检索阶段"),
                    QuestionOption.of("需要补充修改", "在下一步中输入补充内容"),
                    QuestionOption.of("重新分析", "用不同的角度重新分析需求"));

            String answer = questionAgent.ask(projectId,
                    "需求调研已完成！请确认以上分析是否符合你的预期？",
                    confirmOptions, false);

            log.info("Requirement confirmation answer for project {}: {}", projectId, answer);

            if (answer == null || answer.contains("确认无误")) {
                return WorkflowStep.SEARCH_AND_CHARACTER;
            }

            if (answer.contains("重新分析")) {
                log.info("Re-running requirement analysis for project {}", projectId);
                result = RetryTemplate.execute(
                        () -> requirementAgent.execute(projectId),
                        "需求调研Agent（重新分析）"
                );
                if (!result.isSuccess()) {
                    throw new RuntimeException("需求调研失败: " + result.getErrorMessage());
                }
                // 继续循环，再次让用户确认
            } else if (answer.contains("需要补充修改")) {
                // 第二问：收集补充内容（自由文本）
                StreamTracker.updateStep(projectId, "requirement",
                        "等待用户输入补充信息...\n\n", 61);
                String supplement = questionAgent.ask(projectId,
                        "请输入你需要补充或修改的内容：");
                log.info("Got supplementary info for project {}: {}", projectId, supplement);

                if (supplement != null && !supplement.isBlank()
                        && !supplement.startsWith("(")) {
                    result = requirementAgent.executeWithSupplementary(projectId, supplement);
                    if (!result.isSuccess()) {
                        throw new RuntimeException("需求调研失败: " + result.getErrorMessage());
                    }
                }
                // 继续循环，再次让用户确认
            } else {
                // 未知答案，默认继续
                return WorkflowStep.SEARCH_AND_CHARACTER;
            }
        }
    }

    private WorkflowStep executeSearchAndCharacter(Long projectId) {
        log.info("Executing SEARCH_AND_CHARACTER for project {}", projectId);
        stateManager.advanceWorkflow(projectId, WorkflowStep.SEARCH_AND_CHARACTER);

        // 并行执行人设检索和联网搜索
        CompletableFuture<AgentResult> characterFuture = CompletableFuture.supplyAsync(
                () -> RetryTemplate.execute(
                        () -> characterRetrievalAgent.execute(projectId),
                        "人设检索Agent"
                ), executor);

        CompletableFuture<AgentResult> searchFuture = CompletableFuture.supplyAsync(
                () -> RetryTemplate.execute(
                        () -> searchAgent.execute(projectId),
                        "联网搜索Agent"
                ), executor);

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(characterFuture, searchFuture);

        try {
            allFutures.get(120, TimeUnit.SECONDS);
            AgentResult charResult = characterFuture.get();
            if (!charResult.isSuccess()) {
                log.warn("Character retrieval completed with issues: {}", charResult.getErrorMessage());
            }
            AgentResult searchResult = searchFuture.get();
            if (!searchResult.isSuccess()) {
                log.warn("Search completed with issues: {}", searchResult.getErrorMessage());
            }

            // 存储结果供前端 API 读取（Agent 内部已通过 StreamTracker 流式推送）
            if (charResult.getData() != null) {
                searchResultStore.saveCharacterResult(projectId, charResult.getData().toString());
            }
            if (searchResult.getData() != null) {
                searchResultStore.saveSearchResult(projectId, searchResult.getData().toString());
            }

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.completed("SEARCH_AND_CHARACTER", "信息检索与人设分析已完成"));
            // 等 SSE 事件推送到前端后再提问（短暂延迟）
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            // 确认循环
            var confirmOptions = java.util.List.of(
                    QuestionOption.of("确认无误，继续下一步", "信息充分，进入大纲设计"),
                    QuestionOption.of("需要重新检索", "信息不够充分，重新联网搜索"));

            String answer = questionAgent.ask(projectId,
                    "信息检索与人设分析已完成！请在上方卡片中查看详细结果，确认后继续。",
                    confirmOptions, false);

            if (answer != null && answer.contains("重新检索")) {
                log.info("Re-running search for project {}", projectId);
                // 仅重新执行搜索Agent
                AgentResult retryResult = RetryTemplate.execute(
                        () -> searchAgent.execute(projectId),
                        "联网搜索Agent（重新检索）"
                );
                if (!retryResult.isSuccess()) {
                    log.warn("Re-search had issues: {}", retryResult.getErrorMessage());
                }
            }

            return WorkflowStep.OUTLINE_DESIGN;
        } catch (Exception e) {
            throw new RuntimeException("信息检索失败: " + e.getMessage(), e);
        }
    }

    private WorkflowStep executeOutlineDesign(Long projectId) {
        log.info("Executing OUTLINE_DESIGN for project {}", projectId);
        stateManager.advanceWorkflow(projectId, WorkflowStep.OUTLINE_DESIGN);

        AgentResult result = RetryTemplate.execute(
                () -> outlineAgent.execute(projectId),
                "大纲设计Agent"
        );

        if (result.isSuccess()) {
            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.completed("OUTLINE_DESIGN", "大纲设计已完成，请在上方卡片中查看详细内容"));
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            var confirmOptions = java.util.List.of(
                    QuestionOption.of("大纲满意，继续剧本生成", "已选择合适的大纲方案"),
                    QuestionOption.of("重新生成大纲", "对当前大纲不满意"));

            String answer = questionAgent.ask(projectId,
                    "大纲已生成！请在上方卡片中查看详细内容并选择你喜欢的方案，确认后继续。",
                    confirmOptions, false);

            if (answer != null && answer.contains("重新生成")) {
                log.info("Re-running outline generation for project {}", projectId);
                result = RetryTemplate.execute(
                        () -> outlineAgent.execute(projectId),
                        "大纲设计Agent（重新生成）"
                );
                if (!result.isSuccess()) {
                    throw new RuntimeException("大纲设计失败: " + result.getErrorMessage());
                }
            }

            return WorkflowStep.SCRIPT_GENERATION;
        }
        throw new RuntimeException("大纲设计失败: " + result.getErrorMessage());
    }

    private WorkflowStep executeScriptGeneration(Long projectId) {
        log.info("Executing SCRIPT_GENERATION for project {}", projectId);
        stateManager.advanceWorkflow(projectId, WorkflowStep.SCRIPT_GENERATION);

        AgentResult result = RetryTemplate.execute(
                () -> scriptAgent.execute(projectId),
                "剧本创作Agent"
        );

        if (result.isSuccess()) {
            // 检查是否有分支点待处理
            Map<String, Object> metadata = result.getMetadata();
            if (metadata != null && Boolean.TRUE.equals(metadata.get("branchPending"))) {
                log.info("Branch point pending at chapter {} for project {}",
                        metadata.get("branchAtChapter"), projectId);

                // 构建分支选项供用户选择
                String optionsJson = (String) metadata.get("branchOptions");
                Long branchPointId = metadata.get("branchPointId") instanceof Integer
                        ? ((Integer) metadata.get("branchPointId")).longValue()
                        : (Long) metadata.get("branchPointId");

                // 解析选项并构建问答
                List<QuestionOption> branchOptions = parseBranchOptions(optionsJson);
                if (!branchOptions.isEmpty()) {
                    String answer = questionAgent.ask(projectId,
                            "剧情分支选择 🎮\n\n在第" + metadata.get("branchAtChapter") + "章结尾，" +
                            metadata.get("branchTitle") + "\n\n请选择后续走向：",
                            branchOptions, false);

                    // 保存用户选择
                    if (answer != null && branchPointId != null) {
                        branchPointRepository.findById(branchPointId).ifPresent(bp -> {
                            bp.setSelectedOption(answer);
                            bp.setStatus("SELECTED");
                            branchPointRepository.save(bp);
                        });
                        log.info("User selected branch option '{}' for branchPoint {}", answer, branchPointId);
                    }

                    // 重新执行剧本生成（会跳过已生成章节，从分支点继续）
                    log.info("Re-running ScriptAgent after branch selection for project {}", projectId);
                    result = RetryTemplate.execute(
                            () -> scriptAgent.execute(projectId),
                            "剧本创作Agent（分支后继续）"
                    );
                    if (!result.isSuccess()) {
                        throw new RuntimeException("剧本创作失败（分支后）: " + result.getErrorMessage());
                    }
                }
            }

            return WorkflowStep.QUALITY_REVIEW;
        }
        throw new RuntimeException("剧本创作失败: " + result.getErrorMessage());
    }

    /**
     * 解析分支选项JSON，构建QuestionOption列表.
     */
    @SuppressWarnings("unchecked")
    private List<QuestionOption> parseBranchOptions(String optionsJson) {
        List<QuestionOption> options = new java.util.ArrayList<>();
        if (optionsJson == null || optionsJson.isBlank()) return options;
        try {
            List<Map<String, String>> rawOptions = objectMapper.readValue(optionsJson, List.class);
            for (Map<String, String> opt : rawOptions) {
                String label = opt.getOrDefault("label", "?");
                String desc = opt.getOrDefault("description", "");
                String tag = opt.getOrDefault("tag", "");
                String displayLabel = label + "（" + tag + "）: " + desc;
                options.add(QuestionOption.of(displayLabel.substring(0, Math.min(displayLabel.length(), 200)),
                        "选择分支 " + label));
            }
        } catch (Exception e) {
            log.warn("Failed to parse branch options: {}", e.getMessage());
        }
        return options;
    }

    private WorkflowStep executeQualityReview(Long projectId) {
        log.info("Executing QUALITY_REVIEW for project {}", projectId);
        stateManager.advanceWorkflow(projectId, WorkflowStep.QUALITY_REVIEW);

        AgentResult result = RetryTemplate.execute(
                () -> reviewAgent.execute(projectId),
                "质量审核Agent"
        );

        if (result.isSuccess()) {
            return WorkflowStep.EXPORT;
        }
        // 审核失败不阻塞流程
        log.warn("Quality review failed but continuing: {}", result.getErrorMessage());
        return WorkflowStep.EXPORT;
    }

    private WorkflowStep executeExport(Long projectId) {
        log.info("Executing EXPORT for project {}", projectId);
        stateManager.advanceWorkflow(projectId, WorkflowStep.EXPORT);

        AgentResult result = RetryTemplate.execute(
                () -> documentAgent.exportDocument(projectId),
                "文档导出Agent"
        );

        if (result.isSuccess()) {
            return WorkflowStep.DONE;
        }
        throw new RuntimeException("文档导出失败: " + result.getErrorMessage());
    }
}
