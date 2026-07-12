package com.erchuang.scriptforge.model.dto;

import com.erchuang.scriptforge.model.enums.WritingStyle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 大纲数据传输对象.
 *
 * @author ScriptForge Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutlineDTO {

    private Long id;
    private Long projectId;
    private Integer versionNumber;
    private String title;
    private String summary;
    private String coreConflict;
    private String emotionalArc;
    private String chapters;
    private Boolean selected;
}
