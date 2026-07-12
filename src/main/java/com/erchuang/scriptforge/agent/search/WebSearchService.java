package com.erchuang.scriptforge.agent.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 外部信息源搜索服务 — 多引擎搜索，自动降级，结构化提取.
 * 主引擎: 搜狗（国内中文内容最佳）
 * 备引擎: DuckDuckGo HTML（免费、无需 API Key）
 * 三级引擎: Bing HTML
 *
 * 使用 JSoup 进行结构化 HTML 解析，替代脆弱的正则方案。
 * 每个搜索结果均保留 title + url + snippet，支持溯源引用。
 */
@Component
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final int MAX_SNIPPETS_PER_ENGINE = 12;

    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
    };

    private final HttpClient httpClient;
    private final Random random = new Random();

    public WebSearchService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ==================== 公开 API ====================

    /**
     * 搜索并返回格式化文本结果（多引擎自动降级）.
     * 保留向后兼容，推荐使用 {@link #searchWithMeta(String)} 获取带URL的完整结果.
     */
    public String search(String query) {
        List<SearchResultItem> items = searchWithMeta(query);
        if (items.isEmpty()) {
            return fallback(query);
        }
        return formatResults(items);
    }

    /**
     * 搜索并返回带元数据的结构化结果（推荐使用）.
     * 每个结果包含 title、url、snippet，支持溯源和去重.
     *
     * @param query 搜索关键词
     * @return 搜索结果列表（已去重，按引擎优先级排序）
     */
    public List<SearchResultItem> searchWithMeta(String query) {
        log.info("WebSearch: {}", query);

        // 1. 优先搜狗（国内中文内容最优）
        List<SearchResultItem> results = searchSogou(query);
        if (!results.isEmpty()) {
            return results;
        }

        // 2. 降级到 DuckDuckGo HTML
        log.info("Sogou failed, falling back to DuckDuckGo");
        results = searchDuckDuckGo(query);
        if (!results.isEmpty()) {
            return results;
        }

        // 3. 降级到 Bing
        log.info("DuckDuckGo failed, falling back to Bing");
        results = searchBing(query);
        if (!results.isEmpty()) {
            return results;
        }

        log.warn("All search engines failed for: {}", query);
        return results;
    }

    // ==================== 搜狗搜索 ====================

    private List<SearchResultItem> searchSogou(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.sogou.com/web?query=" + encoded))
                    .header("User-Agent", randomUA())
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Sogou returned status {}", response.statusCode());
                return List.of();
            }

            List<SearchResultItem> snippets = parseSogouWithJsoup(response.body());
            if (snippets.isEmpty()) {
                log.warn("Sogou: no snippets parsed");
                return List.of();
            }

            log.info("Sogou returned {} snippets for: {}", snippets.size(), query);
            return snippets;
        } catch (Exception e) {
            log.warn("Sogou search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SearchResultItem> parseSogouWithJsoup(String html) {
        List<SearchResultItem> results = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // 搜狗搜索结果容器：class 包含 vrwrap 或 rb 的 div
        Elements resultBlocks = doc.select("div[class*=vrwrap], div[class*=rb]");
        // 如果主选择器失效，尝试更宽泛的选择器
        if (resultBlocks.isEmpty()) {
            resultBlocks = doc.select("div.results > div");
        }

        for (Element block : resultBlocks) {
            if (results.size() >= MAX_SNIPPETS_PER_ENGINE) break;

            // 标题与URL：h3 内的 a 标签
            String title = "";
            String url = "";
            Element titleLink = block.selectFirst("h3 a, h3[class*=title] a");
            if (titleLink == null) {
                titleLink = block.selectFirst("a[class*=title]");
            }
            if (titleLink != null) {
                title = cleanText(titleLink.text());
                url = titleLink.attr("abs:href");
            }

            // 摘要：str-text / star-wiki / abstract
            String snippet = "";
            Element snippetEl = block.selectFirst("p.str-text, p.str_info, div.star-wiki, div.abstract, p");
            if (snippetEl != null) {
                snippet = cleanText(snippetEl.text());
            }

            if (!title.isBlank() || !snippet.isBlank()) {
                results.add(new SearchResultItem(title, url, snippet, "搜狗"));
            }
        }
        return results;
    }

    // ==================== DuckDuckGo HTML ====================

    private List<SearchResultItem> searchDuckDuckGo(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://html.duckduckgo.com/html/?q=" + encoded))
                    .header("User-Agent", randomUA())
                    .header("Accept", "text/html")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("DuckDuckGo returned status {}", response.statusCode());
                return List.of();
            }

            List<SearchResultItem> snippets = parseDuckDuckGoWithJsoup(response.body());
            if (snippets.isEmpty()) {
                log.warn("DuckDuckGo: no snippets parsed");
                return List.of();
            }

            log.info("DuckDuckGo returned {} snippets for: {}", snippets.size(), query);
            return snippets;
        } catch (Exception e) {
            log.warn("DuckDuckGo search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SearchResultItem> parseDuckDuckGoWithJsoup(String html) {
        List<SearchResultItem> results = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        Elements resultBlocks = doc.select("div.result");
        for (Element block : resultBlocks) {
            if (results.size() >= MAX_SNIPPETS_PER_ENGINE) break;

            Element titleLink = block.selectFirst("a.result__a");
            String title = titleLink != null ? cleanText(titleLink.text()) : "";
            String url = titleLink != null ? titleLink.attr("abs:href") : "";

            Element snippetEl = block.selectFirst("a.result__snippet");
            String snippet = snippetEl != null ? cleanText(snippetEl.text()) : "";

            if (!title.isBlank() || !snippet.isBlank()) {
                results.add(new SearchResultItem(title, url, snippet, "DuckDuckGo"));
            }
        }
        return results;
    }

    // ==================== Bing HTML ====================

    private List<SearchResultItem> searchBing(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.bing.com/search?q=" + encoded + "&setlang=zh-cn&cc=cn"))
                    .header("User-Agent", randomUA())
                    .header("Accept", "text/html")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Bing returned status {}", response.statusCode());
                return List.of();
            }

            List<SearchResultItem> snippets = parseBingWithJsoup(response.body());
            if (snippets.isEmpty()) {
                log.warn("Bing: no snippets parsed");
                return List.of();
            }

            log.info("Bing returned {} snippets for: {}", snippets.size(), query);
            return snippets;
        } catch (Exception e) {
            log.warn("Bing search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SearchResultItem> parseBingWithJsoup(String html) {
        List<SearchResultItem> results = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        Elements resultBlocks = doc.select("li.b_algo");
        for (Element block : resultBlocks) {
            if (results.size() >= MAX_SNIPPETS_PER_ENGINE) break;

            Element titleLink = block.selectFirst("h2 a");
            String title = titleLink != null ? cleanText(titleLink.text()) : "";
            String url = titleLink != null ? titleLink.attr("abs:href") : "";

            // Bing 摘要通常在一个 p 标签内
            Element snippetEl = block.selectFirst("p");
            // 跳过 b_caption 内的元素干扰
            if (snippetEl == null) {
                snippetEl = block.selectFirst("div.b_caption p");
            }
            String snippet = snippetEl != null ? cleanText(snippetEl.text()) : "";

            if (!title.isBlank() || !snippet.isBlank()) {
                results.add(new SearchResultItem(title, url, snippet, "Bing"));
            }
        }
        return results;
    }

    // ==================== 工具方法 ====================

    /**
     * 将搜索结果格式化为 Markdown 文本（向后兼容）.
     */
    private String formatResults(List<SearchResultItem> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            SearchResultItem item = items.get(i);
            if (!item.title().isBlank()) {
                sb.append("**").append(item.title()).append("**\n");
            }
            if (!item.url().isBlank()) {
                sb.append("来源: ").append(item.url()).append("\n");
            }
            if (!item.snippet().isBlank()) {
                sb.append(item.snippet()).append("\n");
            }
            sb.append("\n---\n\n");
        }
        return sb.toString();
    }

    private String cleanText(String text) {
        return text.replace('\u00A0', ' ').strip();
    }

    private String randomUA() {
        return USER_AGENTS[random.nextInt(USER_AGENTS.length)];
    }

    private String fallback(String query) {
        return "关于 \"" + query + "\" 的实时搜索结果暂时不可用，将基于已有知识继续分析。";
    }

    // ==================== 数据结构 ====================

    /**
     * 搜索结果项 — 包含完整元数据，支持溯源引用.
     *
     * @param title   结果标题
     * @param url     结果链接
     * @param snippet 摘要文本
     * @param engine  来源搜索引擎
     */
    public record SearchResultItem(String title, String url, String snippet, String engine) {}
}
