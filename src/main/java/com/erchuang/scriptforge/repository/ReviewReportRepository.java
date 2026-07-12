package com.erchuang.scriptforge.repository;

import com.erchuang.scriptforge.model.entity.ReviewReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 审核报告数据访问接口.
 *
 * @author ScriptForge Team
 */
@Repository
public interface ReviewReportRepository extends JpaRepository<ReviewReport, Long> {

    /**
     * 按项目ID查找所有审核报告.
     *
     * @param projectId 项目ID
     * @return 审核报告列表
     */
    List<ReviewReport> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /**
     * 按剧本ID查找审核报告.
     *
     * @param scriptId 剧本ID
     * @return 审核报告列表
     */
    List<ReviewReport> findByScriptIdOrderByCreatedAtDesc(Long scriptId);

    /**
     * 按项目ID删除所有审核报告.
     */
    @Modifying
    @Query("DELETE FROM ReviewReport r WHERE r.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
