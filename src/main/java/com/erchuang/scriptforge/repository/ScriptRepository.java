package com.erchuang.scriptforge.repository;

import com.erchuang.scriptforge.model.entity.Script;
import com.erchuang.scriptforge.model.enums.ScriptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 剧本数据访问接口.
 *
 * @author ScriptForge Team
 */
@Repository
public interface ScriptRepository extends JpaRepository<Script, Long> {

    /**
     * 按项目ID查找所有剧本.
     *
     * @param projectId 项目ID
     * @return 剧本列表
     */
    List<Script> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /**
     * 按项目ID和状态查找剧本.
     *
     * @param projectId 项目ID
     * @param status    剧本状态
     * @return 剧本列表
     */
    List<Script> findByProjectIdAndStatus(Long projectId, ScriptStatus status);

    /**
     * 统计项目的剧本数量.
     *
     * @param projectId 项目ID
     * @return 剧本数量
     */
    long countByProjectId(Long projectId);

    /**
     * 按项目ID删除所有剧本.
     */
    @Modifying
    @Query("DELETE FROM Script s WHERE s.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
