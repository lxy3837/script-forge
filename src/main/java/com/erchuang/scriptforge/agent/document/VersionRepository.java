package com.erchuang.scriptforge.agent.document;

import com.erchuang.scriptforge.model.entity.DocumentVersion;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.repository.DocumentVersionRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 版本存储仓库——封装版本快照的存储与查询逻辑.
 *
 * @author ScriptForge Team
 */
@Component
public class VersionRepository {

    private final DocumentVersionRepository documentVersionRepository;
    private final DiffEngine diffEngine;

    public VersionRepository(DocumentVersionRepository documentVersionRepository,
                              DiffEngine diffEngine) {
        this.documentVersionRepository = documentVersionRepository;
        this.diffEngine = diffEngine;
    }

    /**
     * 保存版本快照.
     *
     * @param project   项目
     * @param content   版本内容
     * @param versionTag 版本标签
     * @return 保存的版本快照实体
     */
    public DocumentVersion saveVersion(Project project, String content, String versionTag) {
        Integer maxVersion = documentVersionRepository.findMaxVersionNumberByProjectId(project.getId());
        int newVersion = (maxVersion != null ? maxVersion : 0) + 1;

        DocumentVersion version = DocumentVersion.builder()
                .project(project)
                .versionNumber(newVersion)
                .content(content)
                .versionTag(versionTag)
                .deleted(false)
                .build();

        return documentVersionRepository.save(version);
    }

    /**
     * 获取项目所有未删除的版本.
     */
    public List<DocumentVersion> getVersions(Long projectId) {
        return documentVersionRepository.findByProjectIdAndDeletedFalseOrderByVersionNumberDesc(projectId);
    }

    /**
     * 逻辑删除版本.
     */
    public void softDeleteVersion(Long versionId) {
        documentVersionRepository.findById(versionId).ifPresent(version -> {
            version.setDeleted(true);
            documentVersionRepository.save(version);
        });
    }
}
