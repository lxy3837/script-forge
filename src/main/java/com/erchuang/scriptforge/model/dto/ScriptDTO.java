package com.erchuang.scriptforge.model.dto;

import com.erchuang.scriptforge.model.enums.ScriptStatus;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 剧本数据传输对象.
 *
 * @author ScriptForge Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScriptDTO {

    private Long id;
    private Long projectId;
    private Long outlineId;
    private String title;
    private WritingStyle writingStyle;
    private ScriptStatus status;
    private Integer totalChapters;
    private List<ChapterDTO> chapters;
    /** 组装后的完整剧本 Markdown 文本，供前端直接渲染 */
    private String fullScript;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 章节子DTO.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChapterDTO {
        private Long id;
        private Integer chapterNumber;
        private String title;
        private String scenes;
        private String rawContent;
        private Integer sceneCount;
    }
}
