package com.erchuang.scriptforge.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 大纲实体——每项目最多3版差异化大纲.
 *
 * @author ScriptForge Team
 */
@Entity
@Table(name = "outline", indexes = {
        @Index(name = "idx_outline_project", columnList = "project_id"),
        @Index(name = "idx_outline_selected", columnList = "project_id, selected")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Outline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_outline_project"))
    private Project project;

    /** 版本号（1-3） */
    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    /** 大纲标题 */
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** 故事梗概 */
    @Lob
    @Column(name = "summary")
    private String summary;

    /** 核心冲突描述 */
    @Lob
    @Column(name = "core_conflict")
    private String coreConflict;

    /** 情感走向描述 */
    @Lob
    @Column(name = "emotional_arc")
    private String emotionalArc;

    /** 章节划分（JSON数组，每项含标题+摘要） */
    @Lob
    @Column(name = "chapters")
    private String chapters;

    /** 是否被用户选定为最终大纲 */
    @Column(name = "selected", nullable = false)
    private Boolean selected;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.versionNumber == null) {
            this.versionNumber = 1;
        }
        if (this.selected == null) {
            this.selected = false;
        }
    }
}
