package com.erchuang.scriptforge.model.entity;

import com.erchuang.scriptforge.model.enums.EntryType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 知识库条目实体——全局共享的游戏知识，非项目级.
 *
 * @author ScriptForge Team
 */
@Entity
@Table(name = "knowledge_entry", indexes = {
        @Index(name = "idx_knowledge_game_type", columnList = "game_name, entry_type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属游戏名称 */
    @Column(name = "game_name", length = 100, nullable = false)
    private String gameName;

    /** 条目类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 30, nullable = false)
    private EntryType entryType;

    /** 来源URL（wiki链接等） */
    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    /** 条目标题 */
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** 结构化内容（Markdown格式） */
    @Lob
    @Column(name = "content", nullable = false)
    private String content;

    /** 标签列表（JSON数组） */
    @Column(name = "tags", length = 500)
    private String tags;

    /** 关联人设卡片ID（当 entry_type=CHARACTER 时） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_card_id",
            foreignKey = @ForeignKey(name = "fk_knowledge_character"))
    private CharacterCard characterCard;

    /** 内容最后更新时间 */
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.lastUpdated = now;
    }
}
