package com.erchuang.scriptforge.agent.requirement;

/**
 * 需求摘要构建器——汇总多轮对话记录，生成Markdown格式的需求摘要.
 *
 * @author ScriptForge Team
 */
public class RequirementSummaryBuilder {

    /**
     * 基于项目信息和AI分析结果构建需求摘要.
     *
     * @param projectTitle 项目标题
     * @param gameName     游戏名称
     * @param aiResponse   AI分析结果
     * @return Markdown格式的需求摘要
     */
    public String buildSummary(String projectTitle, String gameName, String aiResponse) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 需求摘要\n\n");
        sb.append("## 项目信息\n\n");
        sb.append("- **项目标题**: ").append(projectTitle).append("\n");
        sb.append("- **目标游戏**: ").append(gameName).append("\n\n");

        sb.append("## AI分析结果\n\n");
        sb.append(aiResponse != null ? aiResponse : "（暂无分析结果）").append("\n\n");

        sb.append("---\n");
        sb.append("*此摘要由需求调研Agent自动生成*\n");
        return sb.toString();
    }
}
