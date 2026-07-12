package com.erchuang.scriptforge.repository;

import com.erchuang.scriptforge.model.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 聊天消息 Repository —— 软删除模式.
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 按项目ID和时间正序获取有效（未被撤回）的历史消息 */
    @Query("SELECT c FROM ChatMessage c WHERE c.projectId = :projectId AND c.active = true ORDER BY c.createdAt ASC")
    List<ChatMessage> findActiveByProject(@Param("projectId") Long projectId);

    /** 获取所有消息（含被撤回的，供历史面板） */
    List<ChatMessage> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    /** 按会话查询活跃消息 */
    @Query("SELECT c FROM ChatMessage c WHERE c.projectId = :projectId AND c.sessionId = :sessionId AND c.active = true ORDER BY c.createdAt ASC")
    List<ChatMessage> findActiveBySession(@Param("projectId") Long projectId, @Param("sessionId") Long sessionId);

    /** 获取项目下所有活跃消息（用于旧数据兼容） */
    @Query("SELECT c FROM ChatMessage c WHERE c.projectId = :projectId AND c.sessionId IS NULL AND c.active = true ORDER BY c.createdAt ASC")
    List<ChatMessage> findActiveLegacy(@Param("projectId") Long projectId);

    /** 获取项目下所有不重复的 sessionId（活跃消息） */
    @Query("SELECT DISTINCT c.sessionId FROM ChatMessage c WHERE c.projectId = :projectId AND c.active = true AND c.sessionId IS NOT NULL ORDER BY c.sessionId ASC")
    List<Long> findActiveSessionIds(@Param("projectId") Long projectId);

    /** 获取指定会话的第一条用户消息（用于会话摘要） */
    @Query("SELECT c FROM ChatMessage c WHERE c.projectId = :projectId AND c.sessionId = :sessionId AND c.role = 'user' AND c.active = true ORDER BY c.createdAt ASC")
    List<ChatMessage> findFirstUserMessageBySession(@Param("projectId") Long projectId, @Param("sessionId") Long sessionId);

    /** 获取指定会话的第一条消息（任意角色，用于获取创建时间） */
    @Query("SELECT c FROM ChatMessage c WHERE c.projectId = :projectId AND c.sessionId = :sessionId AND c.active = true ORDER BY c.createdAt ASC")
    List<ChatMessage> findFirstBySession(@Param("projectId") Long projectId, @Param("sessionId") Long sessionId);

    /** 获取会话的消息数量（排除系统标记消息） */
    @Query("SELECT COUNT(c) FROM ChatMessage c WHERE c.projectId = :projectId AND c.sessionId = :sessionId AND c.active = true AND c.subType <> 'session_start'")
    long countBySession(@Param("projectId") Long projectId, @Param("sessionId") Long sessionId);

    /** 获取项目下最大 sessionId */
    @Query("SELECT COALESCE(MAX(c.sessionId), 0) FROM ChatMessage c WHERE c.projectId = :projectId")
    Long findMaxSessionId(@Param("projectId") Long projectId);

    /** 获取某条消息之前所有活跃消息（用于 fork/分支） */
    @Query("SELECT c FROM ChatMessage c WHERE c.projectId = :projectId AND c.active = true AND c.id <= :messageId ORDER BY c.createdAt ASC")
    List<ChatMessage> findActiveUpTo(@Param("projectId") Long projectId, @Param("messageId") Long messageId);

    /** 软删除：将某条消息及其之后的所有活跃消息标记为 inactive */
    @Modifying
    @Transactional
    @Query("UPDATE ChatMessage c SET c.active = false WHERE c.projectId = :projectId AND c.id >= :messageId AND c.active = true")
    int softDeleteFrom(@Param("projectId") Long projectId, @Param("messageId") Long messageId);

    /** 恢复被撤回的消息 */
    @Modifying
    @Transactional
    @Query("UPDATE ChatMessage c SET c.active = true WHERE c.projectId = :projectId AND c.active = false")
    int restoreAll(@Param("projectId") Long projectId);

    /** 物理删除（清空） */
    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);

    /** 硬删除：物理删除指定消息及其之后的所有消息（不可恢复） */
    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessage c WHERE c.projectId = :projectId AND c.id >= :messageId")
    int hardDeleteFrom(@Param("projectId") Long projectId, @Param("messageId") Long messageId);

    /** 按会话软删除：将指定会话的所有活跃消息标记为 inactive */
    @Modifying
    @Transactional
    @Query("UPDATE ChatMessage c SET c.active = false WHERE c.projectId = :projectId AND c.sessionId = :sessionId AND c.active = true")
    int softDeleteBySession(@Param("projectId") Long projectId, @Param("sessionId") Long sessionId);
}
