package com.erchuang.scriptforge.service;

import com.erchuang.scriptforge.agent.document.DiffEngine;
import com.erchuang.scriptforge.agent.document.VersionRepository;
import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.erchuang.scriptforge.model.entity.DocumentVersion;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.repository.DocumentVersionRepository;
import com.erchuang.scriptforge.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 版本管理服务——版本快照的保存、查询、回退与差异对比.
 *
 * @author ScriptForge Team
 */
@Service
public class VersionService {

    private static final Logger log = LoggerFactory.getLogger(VersionService.class);

    private final ProjectRepository projectRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final VersionRepository versionRepository;
    private final DiffEngine diffEngine;

    public VersionService(ProjectRepository projectRepository,
                           DocumentVersionRepository documentVersionRepository,
                           VersionRepository versionRepository,
                           DiffEngine diffEngine) {
        this.projectRepository = projectRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.versionRepository = versionRepository;
        this.diffEngine = diffEngine;
    }

    /**
     * 创建新版本快照.
     *
     * @param projectId  项目ID
     * @param content    版本内容
     * @param versionTag 版本标签
     * @return 版本快照实体
     */
    @Transactional
    public DocumentVersion createVersion(Long projectId, String content, String versionTag) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "项目不存在: " + projectId));
        return versionRepository.saveVersion(project, content, versionTag);
    }

    /**
     * 获取项目版本列表.
     */
    public List<DocumentVersion> getVersions(Long projectId) {
        return versionRepository.getVersions(projectId);
    }

    /**
     * 获取指定版本.
     */
    public DocumentVersion getVersion(Long versionId) {
        return documentVersionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "版本不存在: " + versionId));
    }

    /**
     * 逻辑删除版本.
     */
    @Transactional
    public void deleteVersion(Long versionId) {
        versionRepository.softDeleteVersion(versionId);
    }

    /**
     * 对比两个版本.
     */
    public String diffVersions(Long versionId1, Long versionId2) {
        DocumentVersion v1 = getVersion(versionId1);
        DocumentVersion v2 = getVersion(versionId2);
        return diffEngine.computeDiff(v1.getContent(), v2.getContent());
    }
}
