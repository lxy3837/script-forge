package com.erchuang.scriptforge.agent.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 搜索结果聚合器 — 聚合多个搜索结果，去重排序，生成结构化摘要.
 *
 * 包含智能分块功能：按语义边界（段落/标题）截断，替代硬截断。
 *
 * @author ScriptForge Team
 */
@Component
public class SearchResultAggregator {

    private static final int CHUNK_SIZE = 4000; // 每块约 4000 字符

    /**
     * 聚合搜索结果，生成结构化摘要.
     *
     * @param aiResponse AI 返回的总结内容
     * @param gameName   游戏名称
     * @return 结构化摘要（Markdown 格式）
     */
    public String aggregate(String aiResponse, String gameName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 游戏《").append(gameName).append("》剧情信息\n\n");

        if (aiResponse != null && !aiResponse.isEmpty()) {
            sb.append(aiResponse).append("\n\n");
        } else {
            sb.append("*暂无搜索结果*\n\n");
        }

        sb.append("---\n");
        sb.append("*搜索时间: ").append(java.time.LocalDateTime.now().toString()).append("*\n");
        return sb.toString();
    }

    /**
     * 聚合搜索结果（带来源列表）.
     *
     * @param aiResponse  AI 总结内容
     * @param gameName    游戏名称
     * @param sourceList  来源列表（[N] URL - 标题）
     * @return 带来源索引的结构化摘要
     */
    public String aggregateWithSources(String aiResponse, String gameName, String sourceList) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 游戏《").append(gameName).append("》剧情信息\n\n");
        sb.append(aggregate(aiResponse, gameName));

        if (sourceList != null && !sourceList.isBlank()) {
            sb.append("\n\n## 信息来源\n\n");
            sb.append(sourceList).append("\n");
        }

        return sb.toString();
    }

    /**
     * 将搜索结果列表格式化为带编号的来源索引.
     *
     * @param items 搜索结果列表
     * @return "[N] 标题 (搜索引擎) - URL" 格式的来源索引
     */
    public String buildSourceIndex(List<WebSearchService.SearchResultItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            WebSearchService.SearchResultItem item = items.get(i);
            sb.append("[").append(i + 1).append("] ");
            if (!item.title().isBlank()) {
                sb.append("**").append(item.title()).append("**");
            }
            sb.append(" (").append(item.engine()).append(")");
            if (!item.url().isBlank()) {
                sb.append(" - ").append(item.url());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 将排名后的结果拼接为 Markdown（按评分降序），仅供带URL+排名的新流程使用.
     *
     * @param rankedResults 已排序的结果
     * @return Markdown 格式文本
     */
    public String formatRankedResults(List<SearchResultRanker.RankedResult> rankedResults) {
        if (rankedResults == null || rankedResults.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rankedResults.size(); i++) {
            SearchResultRanker.RankedResult rr = rankedResults.get(i);
            WebSearchService.SearchResultItem item = rr.item();
            sb.append("### [").append(i + 1).append("] ");
            sb.append(item.title()).append(" (评分: ").append(String.format("%.2f", rr.totalScore())).append(")\n");
            sb.append("来源: ").append(item.url()).append(" | 引擎: ").append(item.engine()).append("\n\n");
            sb.append(item.snippet()).append("\n\n---\n\n");
        }
        return sb.toString();
    }

    // ==================== 智能分块 ====================

    /**
     * 智能分块：按语义边界截断内容，替代硬截断.
     *
     * 策略：
     * 1. 按双换行（段落边界）分割
     * 2. 按 ## 标题保持层级完整
     * 3. 每块不超过 CHUNK_SIZE
     * 4. 总字符数不超过 maxTotalChars
     *
     * @param content       待分块内容
     * @param maxTotalChars 总字符上限
     * @return 分块后重新拼接的文本
     */
    public String smartChunk(String content, int maxTotalChars) {
        if (content == null || content.length() <= maxTotalChars) {
            return content;
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int totalLen = 0;

        // 按段落分割
        String[] paragraphs = content.split("\n\n");

        for (String paragraph : paragraphs) {
            if (totalLen + paragraph.length() > maxTotalChars) break;

            // 当前块满了就开新块
            if (currentChunk.length() + paragraph.length() > CHUNK_SIZE && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
            }

            currentChunk.append(paragraph).append("\n\n");
            totalLen += paragraph.length();
        }

        // 处理剩余内容
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }

        return String.join("\n\n---\n\n", chunks);
    }

    /**
     * 限制搜索结果中每个引擎返回的结果数量（质量优先）.
     *
     * @param items   原始结果列表
     * @param maxPerEngine 每个引擎最多保留的结果数
     * @return 截断后的结果列表
     */
    public List<WebSearchService.SearchResultItem> limitPerEngine(
            List<WebSearchService.SearchResultItem> items, int maxPerEngine) {
        return items.stream()
                .collect(Collectors.groupingBy(WebSearchService.SearchResultItem::engine))
                .values()
                .stream()
                .flatMap(list -> list.stream().limit(maxPerEngine))
                .collect(Collectors.toList());
    }
}
