package com.erchuang.scriptforge.service;

import com.erchuang.scriptforge.agent.character.VectorSearchService;
import com.erchuang.scriptforge.agent.orchestrator.ProjectStateManager;
import com.erchuang.scriptforge.agent.search.SearchResultStore;
import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.model.dto.ProjectDTO;
import com.erchuang.scriptforge.model.entity.CharacterCard;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.model.enums.WorkflowStep;
import com.erchuang.scriptforge.repository.CharacterCardRepository;
import com.erchuang.scriptforge.repository.DocumentVersionRepository;
import com.erchuang.scriptforge.repository.OutlineRepository;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.RequirementRepository;
import com.erchuang.scriptforge.repository.ReviewReportRepository;
import com.erchuang.scriptforge.repository.ScriptChapterRepository;
import com.erchuang.scriptforge.repository.ScriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目管理服务——提供项目的CRUD操作.
 *
 * @author ScriptForge Team
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final OutlineRepository outlineRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptChapterRepository scriptChapterRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final RequirementRepository requirementRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final CharacterCardRepository characterCardRepository;
    private final SearchResultStore searchResultStore;
    private final ProjectStateManager projectStateManager;
    private final SseEmitterService sseEmitterService;
    private final VectorSearchService vectorSearchService;

    public ProjectService(ProjectRepository projectRepository,
                          OutlineRepository outlineRepository,
                          ScriptRepository scriptRepository,
                          ScriptChapterRepository scriptChapterRepository,
                          ReviewReportRepository reviewReportRepository,
                          RequirementRepository requirementRepository,
                          DocumentVersionRepository documentVersionRepository,
                          CharacterCardRepository characterCardRepository,
                          SearchResultStore searchResultStore,
                          ProjectStateManager projectStateManager,
                          SseEmitterService sseEmitterService,
                          VectorSearchService vectorSearchService) {
        this.projectRepository = projectRepository;
        this.outlineRepository = outlineRepository;
        this.scriptRepository = scriptRepository;
        this.scriptChapterRepository = scriptChapterRepository;
        this.reviewReportRepository = reviewReportRepository;
        this.requirementRepository = requirementRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.characterCardRepository = characterCardRepository;
        this.searchResultStore = searchResultStore;
        this.projectStateManager = projectStateManager;
        this.sseEmitterService = sseEmitterService;
        this.vectorSearchService = vectorSearchService;
    }

    /**
     * 创建新项目——自动分配 displayOrder（当前最大值 + 1）.
     */
    @Transactional
    public ProjectDTO createProject(String title, String gameName) {
        if (title == null || title.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "项目标题不能为空");
        }
        if (gameName == null || gameName.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "游戏名称不能为空");
        }

        // 自动分配 displayOrder = MAX(displayOrder) + 1
        Integer maxOrder = projectRepository.findMaxDisplayOrder();
        if (maxOrder == null) maxOrder = 0;

        Project project = Project.builder()
                .title(title.trim())
                .gameName(gameName.trim())
                .status(ProjectStatus.DRAFT)
                .currentStep(WorkflowStep.INIT.getCode())
                .displayOrder(maxOrder + 1)
                .build();

        project = projectRepository.save(project);
        log.info("Created project: id={}, displayOrder={}, title={}",
                project.getId(), project.getDisplayOrder(), project.getTitle());

        return toDto(project);
    }

    /**
     * 获取项目详情.
     */
    public ProjectDTO getProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "项目不存在: id=" + projectId));
        return toDto(project);
    }

    /**
     * 获取所有项目列表（按 displayOrder 升序）.
     */
    public List<ProjectDTO> listProjects() {
        return projectRepository.findAllByOrderByDisplayOrderAsc().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * 按状态获取项目列表（按 displayOrder 升序）.
     */
    public List<ProjectDTO> listProjectsByStatus(ProjectStatus status) {
        return projectRepository.findByStatus(status).stream()
                .sorted((a, b) -> Integer.compare(
                        a.getDisplayOrder() != null ? a.getDisplayOrder() : 0,
                        b.getDisplayOrder() != null ? b.getDisplayOrder() : 0))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * 更新项目标题.
     */
    @Transactional
    public ProjectDTO updateProjectTitle(Long projectId, String newTitle) {
        if (newTitle == null || newTitle.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "新标题不能为空");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "项目不存在: id=" + projectId));
        project.setTitle(newTitle.trim());
        project = projectRepository.save(project);
        return toDto(project);
    }

    /**
     * 更新项目状态.
     */
    @Transactional
    public ProjectDTO updateProjectStatus(Long projectId, ProjectStatus status) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "项目不存在: id=" + projectId));
        project.setStatus(status);
        project = projectRepository.save(project);
        return toDto(project);
    }

    /**
     * 删除项目及其所有关联数据（完整级联清理 + displayOrder重排）.
     */
    @Transactional
    public void deleteProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "项目不存在: id=" + projectId));

        int deletedOrder = project.getDisplayOrder() != null ? project.getDisplayOrder() : 0;

        // 1. 关闭 SSE 连接
        sseEmitterService.closeEmitter(projectId);

        // 2. 删除 CharacterCard（含Lucene索引清理）
        List<CharacterCard> cards = characterCardRepository.findByProjectId(projectId);
        for (CharacterCard card : cards) {
            vectorSearchService.removeCharacterCard(card.getId());
        }
        characterCardRepository.deleteByProjectId(projectId);
        if (!cards.isEmpty()) {
            log.info("Deleted {} CharacterCards and their Lucene indices for project {}", cards.size(), projectId);
        }

        // 3. 按外键依赖顺序级联删除数据库记录
        documentVersionRepository.deleteByProjectId(projectId);   // FK to Project
        reviewReportRepository.deleteByProjectId(projectId);      // FK to Project, Script
        scriptChapterRepository.deleteByProjectId(projectId);     // FK to Script
        scriptRepository.deleteByProjectId(projectId);            // FK to Project, Outline
        outlineRepository.deleteByProjectId(projectId);           // FK to Project
        requirementRepository.deleteByProjectId(projectId);       // FK to Project

        // 4. 删除项目本身
        projectRepository.deleteById(projectId);

        // 5. 清理非数据库资源
        searchResultStore.clear(projectId);                       // 删除 ./data/search/{id}_*.txt
        projectStateManager.clearContext(projectId);              // 清除内存缓存

        // 6. displayOrder 重排：将后面的项目序号前移
        if (deletedOrder > 0) {
            projectRepository.decrementDisplayOrderAfter(deletedOrder);
            log.info("Reindexed displayOrder for projects after order {}", deletedOrder);
        }

        log.info("Cascade deleted project: id={}, displayOrder={}, deletedCards={}",
                projectId, deletedOrder, cards.size());
    }

    /**
     * Entity转DTO.
     */
    private ProjectDTO toDto(Project project) {
        return ProjectDTO.builder()
                .id(project.getId())
                .title(project.getTitle())
                .status(project.getStatus())
                .gameName(project.getGameName())
                .currentStep(project.getCurrentStep())
                .displayOrder(project.getDisplayOrder())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }
}
