package com.erchuang.scriptforge.repository;

import com.erchuang.scriptforge.model.entity.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 版本快照数据访问接口.
 *
 * @author ScriptForge Team
 */
@Repository
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    /**
     * 按项目ID查找所有未删除的版本（按版本号降序）.
     *
     * @param projectId 项目ID
     * @return 版本列表
     */
    List<DocumentVersion> findByProjectIdAndDeletedFalseOrderByVersionNumberDesc(Long projectId);

    /**
     * 按项目ID和版本号查找未删除的版本.
     *
     * @param projectId     项目ID
     * @param versionNumber 版本号
     * @return 版本快照（可能为空）
     */
    Optional<DocumentVersion> findByProjectIdAndVersionNumberAndDeletedFalse(Long projectId, Integer versionNumber);

    /**
     * 获取项目的最大版本号.
     *
     * @param projectId 项目ID
     * @return 最大版本号（可能为null）
     */
    @Query("SELECT MAX(dv.versionNumber) FROM DocumentVersion dv WHERE dv.project.id = :projectId")
    Integer findMaxVersionNumberByProjectId(@Param("projectId") Long projectId);

    /**
     * 按项目ID删除所有版本快照.
     */
    @Modifying
    @Query("DELETE FROM DocumentVersion dv WHERE dv.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
