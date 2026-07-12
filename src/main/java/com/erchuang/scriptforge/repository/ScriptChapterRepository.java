package com.erchuang.scriptforge.repository;

import com.erchuang.scriptforge.model.entity.ScriptChapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 剧本章节数据访问接口.
 *
 * @author ScriptForge Team
 */
@Repository
public interface ScriptChapterRepository extends JpaRepository<ScriptChapter, Long> {

    /**
     * 按剧本ID查找所有章节（按章节号排序）.
     *
     * @param scriptId 剧本ID
     * @return 章节列表
     */
    List<ScriptChapter> findByScriptIdOrderByChapterNumberAsc(Long scriptId);

    /**
     * 统计剧本的章节数.
     *
     * @param scriptId 剧本ID
     * @return 章节数
     */
    long countByScriptId(Long scriptId);

    /**
     * 按剧本ID和章节号查找.
     *
     * @param scriptId      剧本ID
     * @param chapterNumber 章节号
     * @return 章节（可能为空）
     */
    Optional<ScriptChapter> findByScriptIdAndChapterNumber(Long scriptId, Integer chapterNumber);

    /**
     * 删除剧本的所有章节.
     *
     * @param scriptId 剧本ID
     */
    @Modifying
    @Query("DELETE FROM ScriptChapter sc WHERE sc.script.id = :scriptId")
    void deleteByScriptId(@Param("scriptId") Long scriptId);

    /**
     * 按项目ID删除所有章节（通过剧本关联）.
     */
    @Modifying
    @Query("DELETE FROM ScriptChapter sc WHERE sc.script.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
