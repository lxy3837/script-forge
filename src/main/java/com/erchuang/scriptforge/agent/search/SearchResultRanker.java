package com.erchuang.scriptforge.agent.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;

/**
 * 搜索结果质量评估与排序器.
 *
 * 排序策略（多因子加权）：
 * 1. 来源权威性评分（官方 > Wiki > 知名媒体 > 社区 > 个人博客）
 * 2. 标题相关性评分（基于关键词匹配）
 * 3. 内容质量评分（摘要长度 + 正文长度）
 * 4. 时效性评分（发布时间越近权重越高）
 *
 * @author ScriptForge Team
 */
@Component
public class SearchResultRanker {

    private static final Logger log = LoggerFactory.getLogger(SearchResultRanker.class);

    // 权威域名权重表 — 域名 -> 权威性分数 (0.0 ~ 1.0)
    private static final Map<String, Double> AUTHORITY_WEIGHTS = Map.ofEntries(
            // 官方渠道 (1.0 ~ 0.90)
            Map.entry("hoyoverse.com", 1.00),
            Map.entry("mihoyo.com", 1.00),
            Map.entry("genshin.hoyoverse.com", 1.00),
            Map.entry("hsr.hoyoverse.com", 1.00),
            Map.entry("honkaiimpact3.hoyoverse.com", 1.00),
            // 官方社区
            Map.entry("miyoushe.com", 0.92),
            // Wiki 类 (0.90 ~ 0.80)
            Map.entry("wiki.biligame.com", 0.88),
            Map.entry("zh.moegirl.org.cn", 0.85),
            Map.entry("baike.baidu.com", 0.82),
            Map.entry("zh.wikipedia.org", 0.80),
            // 知名游戏媒体 (0.75 ~ 0.65)
            Map.entry("gamersky.com", 0.72),
            Map.entry("3dmgame.com", 0.70),
            Map.entry("ali213.net", 0.68),
            Map.entry("yxdown.com", 0.65),
            // 社区/视频平台 (0.65 ~ 0.50)
            Map.entry("bilibili.com", 0.62),
            Map.entry("nga.cn", 0.58),
            Map.entry("nga.178.com", 0.58),
            Map.entry("zhihu.com", 0.50),
            Map.entry("tieba.baidu.com", 0.45)
    );

    private static final double DEFAULT_AUTHORITY = 0.30;

    /**
     * 对搜索结果进行综合评分和排序.
     *
     * @param rawResults    原始搜索结果
     * @param originalQuery 原始搜索查询
     * @return 按分数降序排列的结果
     */
    public List<RankedResult> rank(List<WebSearchService.SearchResultItem> rawResults,
                                    String originalQuery) {
        if (rawResults == null || rawResults.isEmpty()) {
            return List.of();
        }

        List<RankedResult> ranked = new ArrayList<>();

        for (WebSearchService.SearchResultItem item : rawResults) {
            double authority = getAuthorityScore(item.url());
            double relevance = titleRelevance(item.title(), originalQuery);
            double quality = contentQualityScore(item.snippet());
            double freshness = freshnessScore(item.url()); // 基于域名推断

            // 加权求和
            double score = 0.25 * authority
                         + 0.30 * relevance
                         + 0.25 * quality
                         + 0.20 * freshness;

            ranked.add(new RankedResult(item, score, authority, relevance, quality, freshness));
        }

        // 按分数降序排列
        ranked.sort((a, b) -> Double.compare(b.totalScore, a.totalScore));
        return ranked;
    }

    // ==================== 评分因子 ====================

    /**
     * 来源权威性评分 — 基于域名匹配.
     */
    private double getAuthorityScore(String url) {
        if (url == null || url.isBlank()) return DEFAULT_AUTHORITY;
        String domain = extractDomain(url);
        if (domain.isEmpty()) return DEFAULT_AUTHORITY;

        // 精确匹配
        if (AUTHORITY_WEIGHTS.containsKey(domain)) {
            return AUTHORITY_WEIGHTS.get(domain);
        }

        // 包含匹配
        for (var entry : AUTHORITY_WEIGHTS.entrySet()) {
            if (domain.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return DEFAULT_AUTHORITY;
    }

    /**
     * 标题相关性 — 简化的 TF 计算 + 长度惩罚.
     */
    private double titleRelevance(String title, String query) {
        if (title == null || title.isBlank()) return 0.2;
        if (query == null || query.isBlank()) return 0.5;

        String titleLower = title.toLowerCase();
        String queryLower = query.toLowerCase();

        // 计算查询词在标题中的命中率
        long matchCount = Arrays.stream(queryLower.split("\\s+"))
                .filter(word -> word.length() >= 2 && titleLower.contains(word))
                .count();

        long totalWords = Arrays.stream(queryLower.split("\\s+"))
                .filter(w -> w.length() >= 2)
                .count();

        if (totalWords == 0) return 0.5;

        double ratio = (double) matchCount / totalWords;
        return Math.min(1.0, ratio + 0.2); // 基础分 0.2 + 匹配分
    }

    /**
     * 内容质量 — 基于摘要长度.
     */
    private double contentQualityScore(String snippet) {
        if (snippet == null || snippet.isBlank()) return 0.15;
        int len = snippet.length();
        // 50字=0.15分, 200字=1.0分, 线性映射
        return Math.min(1.0, 0.15 + (len - 50) * 0.0057);
    }

    /**
     * 时效性 — 基于URL特征推断（简化版）.
     * 包含年份/日期的URL更可能是近期内容.
     */
    private double freshnessScore(String url) {
        if (url == null) return 0.5;
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);

        // 检查URL中是否包含近2年的年份
        for (int year = currentYear; year >= currentYear - 2; year--) {
            if (url.contains(String.valueOf(year))) {
                return 0.9;
            }
        }
        // 包含"news"/"article"/"update"等时效性关键词
        if (url.matches("(?i).*(news|article|update|version|patch|event).*")) {
            return 0.75;
        }
        return 0.5;
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost().replace("www.", "").toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 数据结构 ====================

    /**
     * 带评分的搜索结果.
     */
    public record RankedResult(
            WebSearchService.SearchResultItem item,
            double totalScore,
            double authorityScore,
            double relevanceScore,
            double qualityScore,
            double freshnessScore
    ) {}
}
