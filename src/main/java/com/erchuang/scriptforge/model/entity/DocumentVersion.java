package com.erchuang.scriptforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 版本快照实体——逻辑删除，不可物理删除.
 *
 * @author ScriptForge Team
 */
@Entity
@Table(name = "document_version", indexes = {
        @Index(name = "idx_version_project", columnList = "project_id"),
        @Index(name = "idx_version_not_deleted", columnList = "project_id, deleted")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_version_project"))
    private Project project;

    /** 版本号（递增） */
    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    /** 快照内容（完整剧本Markdown） */
    @Lob
    @Column(name = "content", nullable = false)
    private String content;

    /** 与上一版本的差异对比（JSON unified diff） */
    @Lob
    @Column(name = "diff_from_previous")
    private String diffFromPrevious;

    /** 版本标签（如"初稿"/"审核修订"/"终稿"） */
    @Column(name = "version_tag", length = 100)
    private String versionTag;

    /** 逻辑删除标记 */
    @Column(name = "deleted", nullable = false)
    private Boolean deleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.versionNumber == null) {
            this.versionNumber = 1;
        }
        if (this.deleted == null) {
            this.deleted = false;
        }
    }
}
