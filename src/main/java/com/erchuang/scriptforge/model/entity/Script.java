package com.erchuang.scriptforge.model.entity;

import com.erchuang.scriptforge.model.enums.ScriptStatus;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 剧本主表实体——每个项目可有多个剧本版本.
 *
 * @author ScriptForge Team
 */
@Entity
@Table(name = "script", indexes = {
        @Index(name = "idx_script_project", columnList = "project_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Script {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_script_project"))
    private Project project;

    /** 关联的选定大纲（可选，大纲可能被覆盖更新） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "outline_id",
            foreignKey = @ForeignKey(name = "fk_script_outline"))
    private Outline outline;

    /** 剧本标题 */
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** 文风 */
    @Enumerated(EnumType.STRING)
    @Column(name = "writing_style", length = 30, nullable = false)
    private WritingStyle writingStyle;

    /** 剧本状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ScriptStatus status;

    /** 总章数 */
    @Column(name = "total_chapters", nullable = false)
    private Integer totalChapters;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.writingStyle == null) {
            this.writingStyle = WritingStyle.LIGHT_NOVEL;
        }
        if (this.status == null) {
            this.status = ScriptStatus.DRAFT;
        }
        if (this.totalChapters == null) {
            this.totalChapters = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
