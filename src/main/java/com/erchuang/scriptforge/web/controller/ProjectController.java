package com.erchuang.scriptforge.web.controller;

import com.erchuang.scriptforge.agent.search.SearchResultStore;
import com.erchuang.scriptforge.infra.ApiResponse;
import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.erchuang.scriptforge.model.dto.ProjectDTO;
import com.erchuang.scriptforge.model.dto.ReviewReportDTO;
import com.erchuang.scriptforge.model.dto.ScriptDTO;
import com.erchuang.scriptforge.model.entity.*;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.repository.*;
import com.erchuang.scriptforge.service.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 项目管理 REST 接口.
 *
 * @author ScriptForge Team
 */
@RestController
@RequestMapping("/api/projects")
@Validated
public class ProjectController {

    private final ProjectService projectService;
    private final RequirementRepository requirementRepository;
    private final OutlineRepository outlineRepository;
    private final SearchResultStore searchResultStore;
    private final ScriptRepository scriptRepository;
    private final ScriptChapterRepository scriptChapterRepository;
    private final ReviewReportRepository reviewReportRepository;

    @Value("${app.workspace-dir:./workspaces}")
    private String workspaceDir;

    public ProjectController(ProjectService projectService, RequirementRepository requirementRepository,
                              OutlineRepository outlineRepository, SearchResultStore searchResultStore,
                              ScriptRepository scriptRepository, ScriptChapterRepository scriptChapterRepository,
                              ReviewReportRepository reviewReportRepository) {
        this.projectService = projectService;
        this.requirementRepository = requirementRepository;
        this.outlineRepository = outlineRepository;
        this.searchResultStore = searchResultStore;
        this.scriptRepository = scriptRepository;
        this.scriptChapterRepository = scriptChapterRepository;
        this.reviewReportRepository = reviewReportRepository;
    }

    /**
     * 创建新项目.
     */
    @PostMapping
    public ApiResponse<ProjectDTO> createProject(@Valid @RequestBody CreateProjectRequest request) {
        ProjectDTO project = projectService.createProject(request.getTitle(), request.getGameName());
        return ApiResponse.success("项目创建成功", project);
    }

    /**
     * 获取项目列表.
     */
    @GetMapping
    public ApiResponse<List<ProjectDTO>> listProjects(
            @RequestParam(required = false) String status) {
        List<ProjectDTO> projects;
        if (status != null && !status.isEmpty()) {
            ProjectStatus ps = ProjectStatus.valueOf(status.toUpperCase());
            projects = projectService.listProjectsByStatus(ps);
        } else {
            projects = projectService.listProjects();
        }
        return ApiResponse.success(projects);
    }

    /**
     * 获取项目详情.
     */
    @GetMapping("/{id}")
    public ApiResponse<ProjectDTO> getProject(@PathVariable Long id) {
        ProjectDTO project = projectService.getProject(id);
        return ApiResponse.success(project);
    }

    /**
     * 更新项目标题.
     */
    @PutMapping("/{id}/title")
    public ApiResponse<ProjectDTO> updateTitle(@PathVariable Long id,
                                                @Valid @RequestBody UpdateTitleRequest request) {
        ProjectDTO project = projectService.updateProjectTitle(id, request.getTitle());
        return ApiResponse.success(project);
    }

    /**
     * 获取项目的需求分析内容.
     */
    @GetMapping("/{id}/requirement")
    public ApiResponse<Requirement> getRequirement(@PathVariable Long id) {
        Requirement requirement = requirementRepository.findByProjectId(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "需求不存在: 项目 " + id));
        return ApiResponse.success(requirement);
    }

    /**
     * 获取信息检索与人设分析结果.
     */
    @GetMapping("/{id}/search-character")
    public ApiResponse<Map<String, String>> getSearchCharacter(@PathVariable Long id) {
        Map<String, String> result = new HashMap<>();
        String search = searchResultStore.getSearchResult(id);
        String character = searchResultStore.getCharacterResult(id);
        if (search != null) result.put("searchContent", search);
        if (character != null) result.put("characterContent", character);
        result.putIfAbsent("searchContent", "");
        result.putIfAbsent("characterContent", "");
        return ApiResponse.success(result);
    }

    /**
     * 获取项目的大纲列表.
     */
    @GetMapping("/{id}/outlines")
    public ApiResponse<List<Outline>> getOutlines(@PathVariable Long id) {
        List<Outline> outlines = outlineRepository.findByProjectIdOrderByVersionNumberAsc(id);
        return ApiResponse.success(outlines);
    }

    /**
     * 选择某个大纲.
     */
    @PostMapping("/{id}/outlines/select")
    @Transactional
    public ApiResponse<Void> selectOutline(@PathVariable Long id,
                                            @Valid @RequestBody SelectOutlineRequest request) {
        outlineRepository.unselectAllByProjectId(id);
        Outline outline = outlineRepository.findById(request.getSelectedOutlineId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "大纲不存在"));
        outline.setSelected(true);
        outlineRepository.save(outline);
        return ApiResponse.success("已选择大纲方案", null);
    }

    /**
     * 获取项目的最新剧本内容.
     */
    @GetMapping("/{id}/script")
    public ApiResponse<ScriptDTO> getScript(@PathVariable Long id) {
        List<Script> scripts = scriptRepository.findByProjectIdOrderByCreatedAtDesc(id);
        if (scripts.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "剧本不存在: 项目 " + id);
        }
        Script script = scripts.get(0);

        // 加载章节并组装 fullScript
        List<ScriptChapter> chapters = scriptChapterRepository.findByScriptIdOrderByChapterNumberAsc(script.getId());
        List<ScriptDTO.ChapterDTO> chapterDTOs = chapters.stream()
                .map(ch -> ScriptDTO.ChapterDTO.builder()
                        .id(ch.getId())
                        .chapterNumber(ch.getChapterNumber())
                        .title(ch.getTitle())
                        .rawContent(ch.getRawContent())
                        .scenes(ch.getScenes())
                        .sceneCount(ch.getSceneCount())
                        .build())
                .collect(Collectors.toList());

        StringBuilder fullScriptBuilder = new StringBuilder("# ").append(script.getTitle()).append("\n\n");
        for (ScriptChapter ch : chapters) {
            fullScriptBuilder.append("## 第").append(ch.getChapterNumber()).append("章 ")
                    .append(ch.getTitle()).append("\n\n");
            if (ch.getRawContent() != null) {
                fullScriptBuilder.append(ch.getRawContent()).append("\n\n");
            }
        }

        ScriptDTO dto = ScriptDTO.builder()
                .id(script.getId())
                .projectId(id)
                .outlineId(script.getOutline() != null ? script.getOutline().getId() : null)
                .title(script.getTitle())
                .writingStyle(script.getWritingStyle())
                .status(script.getStatus())
                .totalChapters(script.getTotalChapters())
                .chapters(chapterDTOs)
                .fullScript(fullScriptBuilder.toString())
                .createdAt(script.getCreatedAt())
                .updatedAt(script.getUpdatedAt())
                .build();

        return ApiResponse.success(dto);
    }

    /**
     * 获取项目的最新审核报告.
     */
    @GetMapping("/{id}/review")
    public ApiResponse<ReviewReportDTO> getReview(@PathVariable Long id) {
        List<ReviewReport> reports = reviewReportRepository.findByProjectIdOrderByCreatedAtDesc(id);
        if (reports.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核报告不存在: 项目 " + id);
        }
        ReviewReport report = reports.get(0);

        // 构建 summary 供前端渲染
        StringBuilder summary = new StringBuilder();
        summary.append("## 综合评分: ").append(report.getOverallScore()).append("\n\n");
        if (report.getOocIssues() != null && !report.getOocIssues().isBlank()) {
            summary.append("### OOC 检测\n").append(report.getOocIssues()).append("\n\n");
        }
        if (report.getLogicIssues() != null && !report.getLogicIssues().isBlank()) {
            summary.append("### 逻辑一致性\n").append(report.getLogicIssues()).append("\n\n");
        }
        if (report.getPacingAnalysis() != null && !report.getPacingAnalysis().isBlank()) {
            summary.append("### 节奏评估\n").append(report.getPacingAnalysis()).append("\n\n");
        }

        ReviewReportDTO dto = ReviewReportDTO.builder()
                .id(report.getId())
                .projectId(id)
                .scriptId(report.getScript() != null ? report.getScript().getId() : null)
                .oocIssues(report.getOocIssues())
                .logicIssues(report.getLogicIssues())
                .pacingAnalysis(report.getPacingAnalysis())
                .summary(summary.toString())
                .overallScore(report.getOverallScore())
                .status(report.getStatus())
                .createdAt(report.getCreatedAt())
                .build();

        return ApiResponse.success(dto);
    }

    /**
     * 更新项目状态.
     */
    @PutMapping("/{id}/status")
    public ApiResponse<ProjectDTO> updateStatus(@PathVariable Long id,
                                                 @Valid @RequestBody UpdateStatusRequest request) {
        ProjectStatus status = ProjectStatus.valueOf(request.getStatus().toUpperCase());
        ProjectDTO project = projectService.updateProjectStatus(id, status);
        return ApiResponse.success(project);
    }

    /**
     * 删除项目.
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteProject(@PathVariable Long id) {
        projectService.deleteProject(id);
        return ApiResponse.success("项目已删除", null);
    }

    /**
     * 读取项目工作空间中的文件内容（供前端编辑器和文件导航使用）.
     */
    @GetMapping("/{id}/files")
    public ApiResponse<Map<String, Object>> getProjectFile(
            @PathVariable Long id,
            @RequestParam String path) {
        try {
            String safe = path.replace('\\', '/').replaceAll("\\.\\./", "").replaceAll("^\\.\\.", "");
            Path filePath = Paths.get(workspaceDir, "project-" + id, safe).normalize();
            if (!Files.exists(filePath)) {
                return ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在: " + path);
            }
            String content = Files.readString(filePath);
            Map<String, Object> result = new HashMap<>();
            result.put("path", path);
            result.put("content", content);
            result.put("size", content.length());
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 获取项目工作空间的文件树结构（递归）.
     */
    @GetMapping("/{id}/files/tree")
    public ApiResponse<List<TreeNode>> getProjectFileTree(@PathVariable Long id) {
        try {
            Path root = Paths.get(workspaceDir, "project-" + id).normalize();
            if (!Files.exists(root)) {
                return ApiResponse.success(new ArrayList<>());
            }
            List<TreeNode> tree = buildTree(root, root);
            return ApiResponse.success(tree);
        } catch (Exception e) {
            return ApiResponse.error(500, "获取文件树失败: " + e.getMessage());
        }
    }

    /** 递归构建文件树节点 */
    private List<TreeNode> buildTree(Path root, Path dir) {
        List<TreeNode> nodes = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> children = stream
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir && !bDir) return -1;
                        if (!aDir && bDir) return 1;
                        return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                    })
                    .collect(Collectors.toList());
            for (Path child : children) {
                TreeNode node = new TreeNode();
                node.setName(child.getFileName().toString());
                String relativePath = root.relativize(child).toString().replace('\\', '/');
                if (Files.isDirectory(child)) {
                    node.setType("folder");
                    node.setChildren(buildTree(root, child));
                } else {
                    node.setType("file");
                    node.setPath(relativePath);
                }
                nodes.add(node);
            }
        } catch (Exception ignored) { }
        return nodes;
    }

    @Data
    public static class TreeNode {
        private String name;
        private String type;  // "file" or "folder"
        private String path;  // relative path (for files only)
        private List<TreeNode> children;
    }

    // ---- Request DTOs ----

    @Data
    public static class CreateProjectRequest {
        @NotBlank(message = "项目标题不能为空")
        private String title;
        @NotBlank(message = "游戏名称不能为空")
        private String gameName;
    }

    @Data
    public static class UpdateTitleRequest {
        @NotBlank(message = "新标题不能为空")
        private String title;
    }

    @Data
    public static class UpdateStatusRequest {
        @NotBlank(message = "状态不能为空")
        private String status;
    }

    @Data
    public static class SelectOutlineRequest {
        @NotNull(message = "大纲ID不能为空")
        private Long selectedOutlineId;
    }
}
