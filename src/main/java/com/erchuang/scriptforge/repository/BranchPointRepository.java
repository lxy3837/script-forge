package com.erchuang.scriptforge.repository;

import com.erchuang.scriptforge.model.entity.BranchPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分支点数据访问层.
 *
 * @author ScriptForge Team
 */
@Repository
public interface BranchPointRepository extends JpaRepository<BranchPoint, Long> {

    /** 按项目ID查找所有分支点，按触发章节排序 */
    List<BranchPoint> findByProjectIdOrderByTriggerChapterAsc(Long projectId);

    /** 按项目ID和触发章节查找未选择的分支点 */
    List<BranchPoint> findByProjectIdAndStatusAndTriggerChapterLessThanEqualOrderByTriggerChapterAsc(
            Long projectId, String status, int chapterNumber);
}
