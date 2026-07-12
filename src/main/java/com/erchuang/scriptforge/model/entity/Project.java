package com.erchuang.scriptforge.model.entity;

import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 项目实体——项目管理的主表.
 *
 * @author ScriptForge Team
 */
@Entity
@Table(name = "project", indexes = {
        @Index(name = "idx_project_status", columnList = "status"),
        @Index(name = "idx_project_game_name", columnList = "game_name")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目标题 */
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** 项目状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ProjectStatus status;

    /** 目标游戏名称 */
    @Column(name = "game_name", length = 100, nullable = false)
    private String gameName;

    /** 当前工作流步骤 */
    @Column(name = "current_step", length = 30)
    private String currentStep;

    /** 显示序号（用于前端列表排序，删除时自动重排） */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = ProjectStatus.DRAFT;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
