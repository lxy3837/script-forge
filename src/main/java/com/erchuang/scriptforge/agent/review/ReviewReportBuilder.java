package com.erchuang.scriptforge.agent.review;

import java.math.BigDecimal;

/**
 * 审核报告构建器——组装多维度审核结果为Markdown格式报告.
 *
 * @author ScriptForge Team
 */
public class ReviewReportBuilder {

    /**
     * 构建审核报告.
     *
     * @param oocResult    OOC检测结果
     * @param logicResult  逻辑检查结果
     * @param pacingResult 节奏分析结果
     * @param overallScore 综合评分
     * @return Markdown格式的审核报告
     */
    public String build(String oocResult, String logicResult, String pacingResult,
                         BigDecimal overallScore) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 剧本质量审核报告\n\n");
        sb.append("## 综合评分: ").append(overallScore).append("/100\n\n");
        sb.append("---\n\n");

        sb.append("## 一、OOC（角色偏离）检测\n\n");
        sb.append(oocResult != null ? oocResult : "*暂无OOC问题*\n");
        sb.append("\n---\n\n");

        sb.append("## 二、逻辑一致性检查\n\n");
        sb.append(logicResult != null ? logicResult : "*暂无逻辑问题*\n");
        sb.append("\n---\n\n");

        sb.append("## 三、节奏评估\n\n");
        sb.append(pacingResult != null ? pacingResult : "*暂无节奏问题*\n");
        sb.append("\n---\n\n");

        sb.append("*此报告由质量审核Agent自动生成，建议结合人工审核*\n");

        return sb.toString();
    }
}
