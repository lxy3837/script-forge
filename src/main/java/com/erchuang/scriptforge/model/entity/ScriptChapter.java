package com.erchuang.scriptforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 剧本章节实体——逐章存储分镜内容.
 *
 * @author ScriptForge Team
 */
@Entity
@Table(name = "script_chapter", indexes = {
        @Index(name = "idx_chapter_script", columnList = "script_id"),
        @Index(name = "uk_chapter_number", columnList = "script_id, chapter_number", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScriptChapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属剧本 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "script_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_chapter_script"))
    private Script script;

    /** 章节序号（从1开始递增） */
    @Column(name = "chapter_number", nullable = false)
    private Integer chapterNumber;

    /** 章节标题 */
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** 分镜内容（JSON数组，每项含场景描述/角色动作/台词对白/镜头建议） */
    @Lob
    @Column(name = "scenes")
    private String scenes;

    /** 原始生成文本（Markdown格式，纯剧本内容，不含AI思考） */
    @Lob
    @Column(name = "raw_content")
    private String rawContent;

    /** AI的思考过程（reasoning_content），独立存储，不混入剧本正文 */
    @Lob
    @Column(name = "reasoning")
    private String reasoning;

    /** 分支点ID（该章节属于哪个分支路径，null表示主路线） */
    @Column(name = "branch_point_id")
    private Long branchPointId;

    /** 本场景数 */
    @Column(name = "scene_count", nullable = false)
    private Integer sceneCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.chapterNumber == null) {
            this.chapterNumber = 1;
        }
        if (this.sceneCount == null) {
            this.sceneCount = 0;
        }
    }
}
