package com.erchuang.scriptforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 分支点实体——存储大纲中定义的剧情分支节点.
 * <p>
 * 每个分支点定义在某个章节位置，提供2-N个选项，
 * 用户选择后决定后续章节的走向，实现交互式分支叙事.
 * </p>
 *
 * @author ScriptForge Team
 */
@Entity
@Table(name = "branch_point", indexes = {
        @Index(name = "idx_branch_project", columnList = "project_id"),
        @Index(name = "idx_branch_outline", columnList = "outline_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_branch_project"))
    private Project project;

    /** 关联的大纲 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "outline_id",
            foreignKey = @ForeignKey(name = "fk_branch_outline"))
    private Outline outline;

    /** 触发分支的章节序号（在该章生成后弹出分支选择） */
    @Column(name = "trigger_chapter", nullable = false)
    private Integer triggerChapter;

    /** 分支标题（展示给用户的叙事问题） */
    @Column(name = "title", length = 500, nullable = false)
    private String title;

    /** 选项列表（JSON数组：[{label:"选项A", description:"描述A", targetBranch:"A"}, ...]） */
    @Lob
    @Column(name = "options", nullable = false)
    private String options;

    /** 用户选择的选项标签（null表示尚未选择） */
    @Column(name = "selected_option", length = 200)
    private String selectedOption;

    /** 用户选择后生成的分支后续章节（JSON数组，章节摘要列表） */
    @Lob
    @Column(name = "branch_chapters")
    private String branchChapters;

    /** 分支状态：PENDING / SELECTED / GENERATED */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = "PENDING";
        }
    }
}
