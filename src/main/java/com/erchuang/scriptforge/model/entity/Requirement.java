package com.erchuang.scriptforge.model.entity;

import com.erchuang.scriptforge.model.enums.ScopeLevel;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 需求摘要实体——1:1关联项目，存储需求调研的最终结果.
 *
 * @author ScriptForge Team
 */
@Entity
@Table(name = "requirement", indexes = {
        @Index(name = "uk_requirement_project", columnList = "project_id", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Requirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联项目ID（1:1） */
    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_requirement_project"))
    private Project project;

    /** 结构化需求摘要（Markdown格式） */
    @Lob
    @Column(name = "summary_content", nullable = false)
    private String summaryContent;

    /** 多轮对话记录（JSON数组） */
    @Lob
    @Column(name = "conversation_history")
    private String conversationHistory;

    /** 目标角色名称列表（JSON数组） */
    @Column(name = "target_characters", length = 2000)
    private String targetCharacters;

    /** 风格偏好 */
    @Enumerated(EnumType.STRING)
    @Column(name = "style_preference", length = 50)
    private WritingStyle stylePreference;

    /** 篇幅级别 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_level", length = 20)
    private ScopeLevel scopeLevel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.scopeLevel == null) {
            this.scopeLevel = ScopeLevel.MEDIUM;
        }
    }
}
