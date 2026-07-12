package com.erchuang.scriptforge.agent.orchestrator;

import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.model.dto.SseEventDTO;
import com.erchuang.scriptforge.model.entity.*;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.model.enums.WorkflowStep;
import com.erchuang.scriptforge.repository.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目状态管理器——管理项目生命周期与持久化项目上下文.
 *
 * @author ScriptForge Team
 */
@Component
public class ProjectStateManager {

    private static final Logger log = LoggerFactory.getLogger(ProjectStateManager.class);

    /** 内存中的项目上下文缓存 */
    private final Map<Long, Map<String, Object>> projectContexts = new ConcurrentHashMap<>();

    private final ProjectRepository projectRepository;
    private final RequirementRepository requirementRepository;
    private final OutlineRepository outlineRepository;
    private final ScriptRepository scriptRepository;
    private final SseEmitterService sseEmitterService;

    public ProjectStateManager(ProjectRepository projectRepository,
                                RequirementRepository requirementRepository,
                                OutlineRepository outlineRepository,
                                ScriptRepository scriptRepository,
                                SseEmitterService sseEmitterService) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.outlineRepository = outlineRepository;
        this.scriptRepository = scriptRepository;
        this.sseEmitterService = sseEmitterService;
    }

    /**
     * 服务器启动时将所有 IN_PROGRESS 项目重置为 PAUSED，
     * 因为工作流进程已随上次关闭而终止。
     */
    @PostConstruct
    public void resetStaleInProgressProjects() {
        List<Project> stale = projectRepository.findByStatus(ProjectStatus.IN_PROGRESS);
        if (stale.isEmpty()) return;
        for (Project p : stale) {
            p.setStatus(ProjectStatus.PAUSED);
            projectRepository.save(p);
        }
        log.info("Reset {} stale IN_PROGRESS projects to PAUSED on startup", stale.size());
    }

    /**
     * 获取项目上下文（内存缓存 + 数据库）。
     */
    public Map<String, Object> getProjectContext(Long projectId) {
        return projectContexts.computeIfAbsent(projectId, id -> {
            Map<String, Object> ctx = new HashMap<>();
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在: " + projectId));
            ctx.put("project", project);
            loadContextFromDatabase(projectId, ctx);
            return ctx;
        });
    }

    /**
     * 更新项目上下文中的值。
     */
    public void updateContext(Long projectId, String key, Object value) {
        Map<String, Object> ctx = getProjectContext(projectId);
        ctx.put(key, value);
    }

    /**
     * 从项目上下文中获取值。
     */
    @SuppressWarnings("unchecked")
    public <T> T getContextValue(Long projectId, String key, Class<T> type) {
        Map<String, Object> ctx = getProjectContext(projectId);
        Object value = ctx.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    /**
     * 推进项目工作流。
     */
    public void advanceWorkflow(Long projectId, WorkflowStep newStep) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在: " + projectId));
        project.setCurrentStep(newStep.getCode());
        project.setStatus(ProjectStatus.IN_PROGRESS);
        projectRepository.save(project);

        updateContext(projectId, "currentStep", newStep);
        log.info("Project {} workflow advanced to {}", projectId, newStep.getDisplayName());

        sseEmitterService.sendProgress(projectId,
                SseEventDTO.running(newStep.getCode(), "进入阶段: " + newStep.getDisplayName(),
                        calculateProgress(newStep)));
    }

    /**
     * 标记项目完成。
     */
    public void markCompleted(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在: " + projectId));
        project.setStatus(ProjectStatus.COMPLETED);
        project.setCurrentStep(WorkflowStep.DONE.getCode());
        projectRepository.save(project);
        log.info("Project {} marked as completed", projectId);
    }

    /**
     * 标记项目已暂停.
     */
    public void markPaused(Long projectId, String stepCode) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在: " + projectId));
        project.setStatus(ProjectStatus.PAUSED);
        project.setCurrentStep(stepCode);
        projectRepository.save(project);
        log.info("Project {} marked as paused at step {}", projectId, stepCode);
    }

    /**
     * 清除项目内存缓存。
     */
    public void clearContext(Long projectId) {
        projectContexts.remove(projectId);
    }

    /**
     * 获取当前工作流步骤。
     */
    public WorkflowStep getCurrentStep(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在: " + projectId));
        if (project.getCurrentStep() == null) {
            return WorkflowStep.INIT;
        }
        return WorkflowStep.fromCode(project.getCurrentStep());
    }

    private void loadContextFromDatabase(Long projectId, Map<String, Object> ctx) {
        // 加载需求摘要
        requirementRepository.findByProjectId(projectId).ifPresent(req -> ctx.put("requirement", req));
        // 加载选定大纲
        outlineRepository.findByProjectIdAndSelectedTrue(projectId).ifPresent(outline -> ctx.put("selectedOutline", outline));
        // 加载最新剧本
        java.util.List<Script> scripts = scriptRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        if (!scripts.isEmpty()) {
            ctx.put("latestScript", scripts.get(0));
        }
    }

    private int calculateProgress(WorkflowStep step) {
        return switch (step) {
            case INIT -> 0;
            case REQUIREMENT_GATHERING -> 15;
            case SEARCH_AND_CHARACTER -> 30;
            case OUTLINE_DESIGN -> 50;
            case SCRIPT_GENERATION -> 70;
            case QUALITY_REVIEW -> 85;
            case EXPORT -> 95;
            case DONE -> 100;
        };
    }
}
