package com.erchuang.scriptforge.repository;

import com.erchuang.scriptforge.model.entity.KnowledgeEntry;
import com.erchuang.scriptforge.model.enums.EntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 知识库条目数据访问接口.
 *
 * @author ScriptForge Team
 */
@Repository
public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, Long> {

    /**
     * 按游戏名称和条目类型查找.
     *
     * @param gameName  游戏名称
     * @param entryType 条目类型
     * @return 知识库条目列表
     */
    List<KnowledgeEntry> findByGameNameAndEntryType(String gameName, EntryType entryType);

    /**
     * 按游戏名称查找所有条目.
     *
     * @param gameName 游戏名称
     * @return 知识库条目列表
     */
    List<KnowledgeEntry> findByGameName(String gameName);

    /**
     * 按标题模糊搜索.
     *
     * @param title 标题关键词
     * @return 知识库条目列表
     */
    List<KnowledgeEntry> findByTitleContaining(String title);

    /**
     * 查找指定游戏和标题的条目.
     *
     * @param gameName 游戏名称
     * @param title    条目标题
     * @return 知识库条目列表
     */
    List<KnowledgeEntry> findByGameNameAndTitle(String gameName, String title);

    /**
     * 关联人设卡片的知识库条目.
     *
     * @param characterCardId 人设卡片ID
     * @return 知识库条目列表
     */
    List<KnowledgeEntry> findByCharacterCardId(Long characterCardId);
}
