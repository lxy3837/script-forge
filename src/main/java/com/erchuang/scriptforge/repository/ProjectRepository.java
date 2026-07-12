package com.erchuang.scriptforge.repository;

import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 项目数据访问接口.
 *
 * @author ScriptForge Team
 */
@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByStatus(ProjectStatus status);
    List<Project> findByGameName(String gameName);
    List<Project> findByStatusAndGameName(ProjectStatus status, String gameName);
    long countByGameName(String gameName);

    /**
     * 全部项目按显示序号升序排列.
     */
    List<Project> findAllByOrderByDisplayOrderAsc();

    /**
     * 获取当前最大 displayOrder，用于新建项目时自动分配.
     */
    @Query("SELECT COALESCE(MAX(p.displayOrder), 0) FROM Project p")
    Integer findMaxDisplayOrder();

    /**
     * 将 displayOrder > 指定值的项目序号减1（用于删除后重排）.
     */
    @Modifying
    @Query("UPDATE Project p SET p.displayOrder = p.displayOrder - 1 WHERE p.displayOrder > :deletedOrder")
    void decrementDisplayOrderAfter(@Param("deletedOrder") Integer deletedOrder);
}
