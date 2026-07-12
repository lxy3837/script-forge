package com.erchuang.scriptforge.repository;

import com.erchuang.scriptforge.model.entity.Outline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 大纲数据访问接口.
 *
 * @author ScriptForge Team
 */
@Repository
public interface OutlineRepository extends JpaRepository<Outline, Long> {

    /**
     * 按项目ID查找所有大纲（按版本号排序）.
     *
     * @param projectId 项目ID
     * @return 大纲列表
     */
    List<Outline> findByProjectIdOrderByVersionNumberAsc(Long projectId);

    /**
     * 查找项目的选定大纲.
     *
     * @param projectId 项目ID
     * @return 选定的大纲（可能为空）
     */
    Optional<Outline> findByProjectIdAndSelectedTrue(Long projectId);

    /**
     * 统计项目的大纲数量.
     *
     * @param projectId 项目ID
     * @return 大纲数量
     */
    long countByProjectId(Long projectId);

    /**
     * 将项目的所有大纲设为未选定.
     *
     * @param projectId 项目ID
     */
    @Modifying
    @Query("UPDATE Outline o SET o.selected = false WHERE o.project.id = :projectId")
    void unselectAllByProjectId(@Param("projectId") Long projectId);

    /**
     * 按项目ID删除所有大纲.
     */
    @Modifying
    @Query("DELETE FROM Outline o WHERE o.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
