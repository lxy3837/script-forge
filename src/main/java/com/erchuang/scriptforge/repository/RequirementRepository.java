package com.erchuang.scriptforge.repository;

import com.erchuang.scriptforge.model.entity.Requirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 需求摘要数据访问接口.
 *
 * @author ScriptForge Team
 */
@Repository
public interface RequirementRepository extends JpaRepository<Requirement, Long> {

    /**
     * 按项目ID查找需求摘要.
     *
     * @param projectId 项目ID
     * @return 需求摘要（可能为空）
     */
    Optional<Requirement> findByProjectId(Long projectId);

    /**
     * 检查项目是否已有需求摘要.
     *
     * @param projectId 项目ID
     * @return 是否存在
     */
    boolean existsByProjectId(Long projectId);

    /**
     * 按项目ID删除需求摘要.
     *
     * @param projectId 项目ID
     */
    void deleteByProjectId(Long projectId);
}
