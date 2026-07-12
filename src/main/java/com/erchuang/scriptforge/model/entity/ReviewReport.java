package com.erchuang.scriptforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审核报告实体——OOC/逻辑/节奏三维度分析.
 *
 * @author ScriptForge Team
 */
@Entity
@Table(name = "review_report", indexes = {
        @Index(name = "idx_review_project", columnList = "project_id"),
        @Index(name = "idx_review_script", columnList = "script_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_review_project"))
    private Project project;

    /** 被审核的剧本 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "script_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_review_script"))
    private Script script;

    /** OOC问题列表（JSON，每项含角色/偏离描述/严重等级/位置定位/修改建议） */
    @Lob
    @Column(name = "ooc_issues")
    private String oocIssues;

    /** 逻辑问题列表（JSON，每项含问题描述/严重等级/位置定位/修改建议） */
    @Lob
    @Column(name = "logic_issues")
    private String logicIssues;

    /** 节奏评估（JSON，含场景密度/情绪起伏/叙事张力） */
    @Lob
    @Column(name = "pacing_analysis")
    private String pacingAnalysis;

    /** 综合评分（0.00 - 100.00） */
    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    /** 报告状态：PENDING / APPROVED / REVISED */
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
