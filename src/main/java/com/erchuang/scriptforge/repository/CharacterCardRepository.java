package com.erchuang.scriptforge.repository;

import com.erchuang.scriptforge.model.entity.CharacterCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 人设卡片数据访问接口.
 *
 * @author ScriptForge Team
 */
@Repository
public interface CharacterCardRepository extends JpaRepository<CharacterCard, Long> {

    /**
     * 按游戏名称和角色名精确查找.
     *
     * @param gameName    游戏名称
     * @param characterName 角色名称
     * @return 人设卡片（可能为空）
     */
    Optional<CharacterCard> findByGameNameAndName(String gameName, String characterName);

    /**
     * 按游戏名称查找所有角色.
     *
     * @param gameName 游戏名称
     * @return 角色列表
     */
    List<CharacterCard> findByGameName(String gameName);

    /**
     * 按角色名精确匹配.
     *
     * @param name 角色名称（精确匹配）
     * @return 角色列表
     */
    List<CharacterCard> findByName(String name);

    /**
     * 按角色名模糊搜索（忽略游戏）.
     *
     * @param name 角色名称（部分匹配）
     * @return 角色列表
     */
    List<CharacterCard> findByNameContaining(String name);

    /**
     * 查找有embedding数据的角色ID列表.
     *
     * @param gameName 游戏名称
     * @return 角色ID列表
     */
    @Query("SELECT c.id FROM CharacterCard c WHERE c.gameName = :gameName AND c.embedding IS NOT NULL")
    List<Long> findIdsWithEmbeddingByGameName(@Param("gameName") String gameName);

    /**
     * 查找所有有embedding数据的角色（非懒加载embedding字段）.
     *
     * @param gameName 游戏名称
     * @return 角色列表
     */
    @Query("SELECT c FROM CharacterCard c WHERE c.gameName = :gameName AND c.embedding IS NOT NULL")
    List<CharacterCard> findAllWithEmbeddingByGameName(@Param("gameName") String gameName);

    /**
     * 按项目ID查找角色卡片.
     */
    List<CharacterCard> findByProjectId(Long projectId);

    /**
     * 按项目ID删除角色卡片.
     */
    void deleteByProjectId(Long projectId);
}
