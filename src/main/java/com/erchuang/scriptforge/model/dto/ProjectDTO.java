package com.erchuang.scriptforge.model.dto;

import com.erchuang.scriptforge.model.enums.ProjectStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 项目数据传输对象.
 *
 * @author ScriptForge Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectDTO {

    private Long id;
    private String title;
    private ProjectStatus status;
    private String gameName;
    private String currentStep;
    private Integer displayOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
