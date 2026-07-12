package com.erchuang.scriptforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 聊天消息实体 —— 存储 ScriptForge Agent 的对话历史.
 * <p>软删除设计：撤回时标记 active=false，不物理删除，用户可在历史面板找回。</p>
 */
@Entity
@Table(name = "chat_message", indexes = {
        @Index(name = "idx_chat_project", columnList = "projectId"),
        @Index(name = "idx_chat_created", columnList = "createdAt"),
        @Index(name = "idx_chat_active", columnList = "projectId,active"),
        @Index(name = "idx_chat_session", columnList = "projectId,sessionId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID */
    @Column(nullable = false)
    private Long projectId;

    /** 角色: user / agent / tool / thinking */
    @Column(nullable = false)
    private String role;

    /** 消息内容 */
    @Column(length = 10000)
    private String content;

    /** 子类型: user / thinking / tool_call / tool_result / reply / error */
    @Column(length = 50)
    private String subType;

    /**
     * 会话 ID —— 用于在一个项目下区分多个独立对话窗口.
     * 同一次"新对话"产生的消息共享同一个 sessionId.
     * null 表示旧数据（无会话概念时创建的消息）.
     */
    @Column
    private Long sessionId;

    /** 软删除标记 - 撤回时设为 false，物理不删 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.active == null) this.active = true;
        this.createdAt = LocalDateTime.now();
    }
}
