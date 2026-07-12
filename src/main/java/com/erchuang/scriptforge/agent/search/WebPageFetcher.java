package com.erchuang.scriptforge.agent.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 网页正文抓取器 — 从搜索结果的URL抓取完整网页内容并清洗.
 *
 * 核心功能：
 * 1. 智能正文提取（基于文本密度算法的简化版 Readability）
 * 2. HTML 标签清洗
 * 3. 元数据提取（标题、发布时间）
 * 4. 选择性抓取（仅抓取搜索结果中评分高的URL）
 *
 * @author ScriptForge Team
 */
@Component
public class WebPageFetcher {

    private static final Logger log = LoggerFactory.getLogger(WebPageFetcher.class);
    private static final int MAX_CONTENT_LENGTH = 8000;
    private static final int FETCH_TIMEOUT_SECONDS = 8;

    /**
     * 抓取并提取网页正文.
     *
     * @param url 目标URL
     * @return 提取的内容，包含标题、正文、发布时间；失败返回 empty
     */
    public Optional<PageContent> fetch(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .timeout((int) Duration.ofSeconds(FETCH_TIMEOUT_SECONDS).toMillis())
                    .followRedirects(true)
                    .get();

            String title = doc.title();
            String publishTime = extractPublishTime(doc);
            String mainContent = extractMainContent(doc);

            if (mainContent.isBlank()) {
                log.debug("No content extracted from {}", url);
                return Optional.empty();
            }

            return Optional.of(new PageContent(
                    url,
                    title != null ? title : "",
                    publishTime,
                    limit(mainContent, MAX_CONTENT_LENGTH)
            ));
        } catch (Exception e) {
            log.debug("Failed to fetch page {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 智能正文提取 — 基于文本密度算法.
     * 优先匹配语义化标签，回退到文本密度评分.
     */
    private String extractMainContent(Document doc) {
        // 移除干扰元素
        doc.select("script, style, nav, footer, header, .sidebar, .advertisement, .comment, .comments, .recommend")
                .remove();

        // 1. 优先：HTML5 语义标签 & 常见内容容器
        String[] contentSelectors = {
                "article", "main", "[role=main]",
                ".content", ".article", ".article-content", ".post-content",
                ".entry", ".entry-content", ".post-body",
                ".markdown-body", ".wiki-content",
                "#content", "#article", "#post"
        };

        for (String selector : contentSelectors) {
            Element el = doc.selectFirst(selector);
            if (el != null) {
                String text = el.text();
                if (text.length() > 200) {
                    return text;
                }
            }
        }

        // 2. 回退：文本密度评分 — 找到正文密度最高的容器
        Element bestBlock = null;
        double bestScore = 0;

        for (Element block : doc.body().children()) {
            double score = textDensityScore(block);
            if (score > bestScore && score > 1.5) {
                bestScore = score;
                bestBlock = block;
            }
        }

        if (bestBlock != null) {
            return bestBlock.text();
        }

        // 3. 最终回退：整个 body 文本
        return doc.body() != null ? doc.body().text() : "";
    }

    /**
     * 文本密度评分：文本密度 × 文本长度因子.
     * 密度越高说明内容越集中（相对于HTML标签），越长说明信息量越大.
     */
    private double textDensityScore(Element element) {
        String text = element.text();
        String html = element.html();
        if (text.isEmpty() || html.isEmpty()) return 0;

        double density = (double) text.length() / html.length();
        double lengthFactor = Math.min(text.length() / 500.0, 3.0);
        return density * lengthFactor;
    }

    /**
     * 提取发布时间 — 尝试多种元数据选择器.
     */
    private String extractPublishTime(Document doc) {
        String[] selectors = {
                "meta[property='article:published_time']",
                "meta[name='publishdate']",
                "meta[name='date']",
                "meta[name='pubdate']",
                "time[datetime]",
                ".publish-time", ".post-time", ".article-time", ".time"
        };

        for (String sel : selectors) {
            Element el = doc.selectFirst(sel);
            if (el != null) {
                if (el.hasAttr("content")) return el.attr("content");
                if (el.hasAttr("datetime")) return el.attr("datetime");
                String text = el.text().strip();
                if (!text.isBlank()) return text;
            }
        }
        return "未知";
    }

    private String limit(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // ==================== 数据结构 ====================

    /**
     * 网页内容 DTO.
     *
     * @param url         来源URL
     * @param title       页面标题
     * @param publishTime 发布时间
     * @param content     清洗后的正文
     */
    public record PageContent(String url, String title, String publishTime, String content) {}
}
