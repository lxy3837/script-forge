package com.erchuang.scriptforge.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工作空间文件写入器 — 为所有工作流 Agent 提供统一的文件输出能力。
 * 写入目录: {@code ./workspaces/project-{id}/}
 */
@Component
public class WorkspaceFileWriter {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFileWriter.class);

    private final String workspaceDir;

    public WorkspaceFileWriter(@Value("${app.workspace-dir:./workspaces}") String workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    /**
     * 将内容写入项目工作空间下的文件。
     *
     * @param projectId 项目 ID
     * @param relativePath 相对路径（如 {@code "需求分析.md"}、{@code "chapters/chapter-1.md"}）
     * @param content 文件内容
     * @return 写入的绝对路径
     */
    public Path write(Long projectId, String relativePath, String content) {
        String safe = relativePath.replace('\\', '/')
                .replaceAll("\\.\\./", "")
                .replaceAll("/\\.\\.", "");
        Path target = Paths.get(workspaceDir, "project-" + projectId, safe).normalize();

        // 安全检查：确保在 workspace 范围内
        Path root = Paths.get(workspaceDir, "project-" + projectId).normalize();
        if (!target.startsWith(root)) {
            log.warn("Path traversal attempt blocked: {}", relativePath);
            target = root.resolve(safe);
        }

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            log.info("Workspace file written: {}", target);
        } catch (IOException e) {
            log.error("Failed to write workspace file {}: {}", target, e.getMessage());
        }
        return target;
    }

    /**
     * 获取项目工作空间根目录。
     */
    public Path getProjectRoot(Long projectId) {
        return Paths.get(workspaceDir, "project-" + projectId).normalize();
    }
}
