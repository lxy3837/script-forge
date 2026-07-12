package com.erchuang.scriptforge.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审核报告数据传输对象.
 *
 * @author ScriptForge Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewReportDTO {

    private Long id;
    private Long projectId;
    private Long scriptId;
    private String oocIssues;
    private String logicIssues;
    private String pacingAnalysis;
    /** 维度摘要拼接，供前端直接渲染 */
    private String summary;
    private BigDecimal overallScore;
    private String status;
    private LocalDateTime createdAt;
}
