package com.erchuang.scriptforge.agent.search;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.infra.WorkspaceFileWriter;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.DeepSeekClient.ChatMessage;
import com.erchuang.scriptforge.model.dto.SseEventDTO;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.entity.Requirement;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.RequirementRepository;
import com.erchuang.scriptforge.stream.StreamTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 实时搜索Agent — 联网搜索最新游戏剧情、版本公告，并用 AI 结构化总结.
 *
 * 优化版特性：
 * - 使用 JSoup 结构化解析，捕获每条结果的 URL，支持溯源引用
 * - 搜索结果按权威性 + 相关性 + 质量 + 时效性 多因子排序
 * - Top-3 结果自动抓取网页正文，丰富信息密度
 * - AI 总结 Prompt 包含事实约束：逐条引用、区分确定性、置信度标注
 * - 智能分块替代硬截断，保持语义完整性
 * - 缓存键包含需求特征，避免不同场景的缓存冲突
 */
@Component
public class RealtimeSearchAgent {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSearchAgent.class);

    private static final int MAX_SEARCH_KEYWORDS = 8;
    private static final int MAX_CONTENT_FOR_AI = 16000; // AI 总结输入上限（字符数）
    private static final int TOP_PAGES_TO_FETCH = 3;     // 抓取全文的 Top-N 结果数
    private static final int SEARCH_TIMEOUT_SECONDS = 60;

    private final ProjectRepository projectRepository;
    private final RequirementRepository requirementRepository;
    private final DeepSeekClient deepSeekClient;
    private final SseEmitterService sseEmitterService;
    private final WebSearchService searchService;
    private final SearchResultAggregator aggregator;
    private final CacheManager cacheManager;
    private final SearchResultRanker ranker;
    private final WebPageFetcher pageFetcher;
    private final ExecutorService searchExecutor = Executors.newFixedThreadPool(4);
    private final WorkspaceFileWriter workspaceFileWriter;

    public RealtimeSearchAgent(ProjectRepository projectRepository,
                                RequirementRepository requirementRepository,
                                DeepSeekClient deepSeekClient,
                                SseEmitterService sseEmitterService,
                                WebSearchService searchService,
                                SearchResultAggregator aggregator,
                                CacheManager cacheManager,
                                SearchResultRanker ranker,
                                WebPageFetcher pageFetcher,
                                WorkspaceFileWriter workspaceFileWriter) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.deepSeekClient = deepSeekClient;
        this.sseEmitterService = sseEmitterService;
        this.searchService = searchService;
        this.aggregator = aggregator;
        this.cacheManager = cacheManager;
        this.ranker = ranker;
        this.pageFetcher = pageFetcher;
        this.workspaceFileWriter = workspaceFileWriter;
    }

    /**
     * 执行实时搜索 — AI 动态生成关键词，多维度并行搜索，排序+抓取全文+AI总结.
     */
    public AgentResult execute(Long projectId) {
        log.info("RealtimeSearchAgent started for project {}", projectId);

        try {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

            String gameName = project.getGameName();
            Requirement requirement = requirementRepository.findByProjectId(projectId).orElse(null);
            String requirementContext = requirement != null ? requirement.getSummaryContent() : "";

            // ========== 1. AI 动态生成搜索关键词 ==========
            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("SEARCH", "AI 正在分析【" + gameName + "】，生成搜索关键词...", 5));

            List<String> keywords = generateSearchKeywords(gameName, requirementContext);
            log.info("AI generated {} search keywords for {}: {}", keywords.size(), gameName, keywords);

            if (keywords.isEmpty()) {
                keywords = List.of(gameName + " 剧情 角色 最新更新");
            }

            // ========== 2. 并行搜索 + 结构化提取 ==========
            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("SEARCH", "正在并行搜索 " + keywords.size() + " 组关键词...", 15));

            List<CompletableFuture<List<WebSearchService.SearchResultItem>>> searchFutures = keywords.stream()
                    .map(kw -> CompletableFuture.supplyAsync(() -> {
                        log.info("Searching: {}", kw);
                        return searchService.searchWithMeta(kw);
                    }, searchExecutor))
                    .collect(Collectors.toList());

            // 收集所有搜索结果的 item（含URL），并用于后续排名
            List<WebSearchService.SearchResultItem> allItems = new ArrayList<>();
            for (int i = 0; i < searchFutures.size(); i++) {
                try {
                    List<WebSearchService.SearchResultItem> items = searchFutures.get(i)
                            .get(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (items != null && !items.isEmpty()) {
                        allItems.addAll(items);
                    }
                } catch (Exception e) {
                    log.warn("Search for '{}' failed: {}", keywords.get(i), e.getMessage());
                }

                int progress = 15 + (40 * (i + 1) / searchFutures.size());
                sseEmitterService.sendProgress(projectId,
                        SseEventDTO.running("SEARCH", "搜索进度: " + (i + 1) + "/" + searchFutures.size(), progress));
            }

            if (allItems.isEmpty()) {
                log.warn("All searches returned empty for {}", gameName);
                String cached = cacheManager.getCachedResult(gameName, requirementContext);
                if (cached != null) {
                    sseEmitterService.sendProgress(projectId,
                            SseEventDTO.completed("SEARCH", "使用缓存搜索结果"));
                    return AgentResult.success(cached, Map.of("source", "cache"));
                }
                return AgentResult.success("暂无相关搜索结果，请尝试更换搜索词或稍后重试。",
                        Map.of("source", "empty"));
            }

            // ========== 3. 搜索结果排序与去重 ==========
            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("SEARCH", "正在评估搜索结果质量并排序...", 58));

            // 合并所有关键词的结果，用URL去重
            allItems = deduplicateByUrl(allItems);

            // 多因子排序
            List<SearchResultRanker.RankedResult> rankedResults = ranker.rank(allItems,
                    String.join(" ", keywords));
            log.info("Ranked {} unique results, top-3: {}",
                    rankedResults.size(),
                    rankedResults.stream().limit(3).map(r -> r.item().title()).collect(Collectors.joining(" | ")));

            // ========== 4. 抓取 Top-N 网页正文 ==========
            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("SEARCH", "正在抓取高分网页正文...", 62));

            List<WebPageFetcher.PageContent> fetchedPages = new ArrayList<>();
            int pagesToFetch = Math.min(TOP_PAGES_TO_FETCH, rankedResults.size());
            for (int i = 0; i < pagesToFetch; i++) {
                String url = rankedResults.get(i).item().url();
                if (url != null && !url.isBlank()) {
                    try {
                        pageFetcher.fetch(url).ifPresent(page -> {
                            fetchedPages.add(page);
                            log.debug("Fetched full content from {} ({} chars)", url, page.content().length());
                        });
                    } catch (Exception e) {
                        log.debug("Page fetch failed for {}: {}", url, e.getMessage());
                    }
                }
            }

            // ========== 5. 构建来源索引 ==========
            StringBuilder sourceIndex = new StringBuilder();
            for (int i = 0; i < rankedResults.size(); i++) {
                var item = rankedResults.get(i).item();
                sourceIndex.append("[").append(i + 1).append("] ");
                sourceIndex.append(item.title()).append(" (").append(item.engine()).append(")");
                if (!item.url().isBlank()) {
                    sourceIndex.append(" - ").append(item.url());
                }
                sourceIndex.append("\n");
            }

            // ========== 6. 拼接所有结果文本（含全文）==========
            StringBuilder allRawResults = new StringBuilder();
            // 先放搜索结果摘要
            allRawResults.append(aggregator.formatRankedResults(rankedResults));
            // 再附加上全文抓取的内容
            if (!fetchedPages.isEmpty()) {
                allRawResults.append("\n## 网页全文抓取\n\n");
                for (int i = 0; i < fetchedPages.size(); i++) {
                    WebPageFetcher.PageContent page = fetchedPages.get(i);
                    allRawResults.append("### 全文 [").append(i + 1).append("] ")
                            .append(page.title()).append("\n");
                    allRawResults.append("来源: ").append(page.url()).append("\n");
                    allRawResults.append("发布时间: ").append(page.publishTime()).append("\n\n");
                    allRawResults.append(page.content()).append("\n\n---\n\n");
                }
            }

            String combinedResults = aggregator.smartChunk(allRawResults.toString(), MAX_CONTENT_FOR_AI);

            // ========== 7. AI 深度总结（增强版Prompt）==========
            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("SEARCH", "AI 正在深度总结 " + allItems.size() + " 条搜索结果...", 65));

            String summaryPrompt = String.format("""
                    你是一个游戏剧情分析专家。请仅基于以下联网搜索结果，全面分析游戏《%s》的最新信息。
                    
                    ## 分析维度
                    1. **最新主线剧情更新**（含版本号、关键情节转折）
                    2. **新角色与角色设定变更**（含声优、属性、阵营等如有提及）
                    3. **近期活动、联动与版本动态**（含起止时间如提及）
                    4. **社区讨论热点与玩家关注焦点**
                    5. **世界观扩展与新地图/新系统**
                    
                    ## 严格规则（必须逐条遵守）
                    1. **逐条引用**：每个事实陈述后标注来源编号 [N]，如"4.2版本新增了枫丹地区地图[3]"
                    2. **区分确定性**：
                       - 搜索结果明确提到 → 直接陈述
                       - 搜索结果暗示/推测 → 标注「*推测*」
                       - 搜索结果存在矛盾 → 标注「*存在争议：A说...B说...*」
                    3. **禁止编造**：完全找不到信息的维度，输出「*该维度当前搜索结果中无相关信息*」，禁止基于训练数据补充
                    4. **置信度标注**：每个章节开头标注信息置信度 **【高】**/**【中】**/**【低】**，基于搜索结果数量和来源质量判断
                    5. **直接输出**：禁止"根据搜索结果""以下是分析"等引导语，直接从第一个分析维度开始输出
                    
                    ## 输出格式
                    使用结构化 Markdown，带 ## 二级标题分章节，便于定位和阅读。
                    
                    ## 联网搜索结果
                    %s
                    
                    ## 来源索引（对应文中 [N] 引用）
                    %s
                    """,
                    gameName,
                    combinedResults,
                    sourceIndex.toString());

            List<ChatMessage> messages = List.of(
                    ChatMessage.system("""
                        你是一个游戏剧情分析专家。你必须严格遵守以下原则：
                        1. 只基于提供的搜索结果作答，绝不编造信息
                        2. 每个事实都要标注来源编号 [N]
                        3. 搜索结果越多，你的总结应该越详细
                        4. 对于不确定的信息，明确标注推测或争议
                        5. 找不到信息的维度，如实标注"无相关信息"
                        """),
                    ChatMessage.user(summaryPrompt)
            );

            // 流式调用 + 逐 chunk 推送到前端
            StreamTracker.startStep(projectId, "search", "联网搜索结果");
            StringBuilder aiResponseBuilder = new StringBuilder();
            CountDownLatch streamLatch = new CountDownLatch(1);
            deepSeekClient.chatStream(messages)
                    .doOnNext(chunk -> {
                        aiResponseBuilder.append(chunk);
                        StreamTracker.updateStep(projectId, "search", chunk, -1);
                    })
                    .doOnComplete(streamLatch::countDown)
                    .doOnError(e -> {
                        log.error("Search stream error: {}", e.getMessage());
                        streamLatch.countDown();
                    })
                    .subscribe();

            try { streamLatch.await(120, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

            String aiResponse = aiResponseBuilder.toString();

            // ========== 8. 聚合与缓存 ==========
            String aggregatedResult = aggregator.aggregateWithSources(aiResponse, gameName, sourceIndex.toString());
            cacheManager.cacheResult(gameName, requirementContext, aggregatedResult);

            StreamTracker.endStep(projectId, "search", "completed", 100);

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.completed("SEARCH", "联网搜索完成（" + keywords.size() + " 组关键词，"
                            + allItems.size() + " 条结果，抓取 " + fetchedPages.size() + " 页全文）"));

            // 写入工作空间文件
            if (aggregatedResult != null && !aggregatedResult.isBlank()) {
                workspaceFileWriter.write(projectId, "联网搜索结果.md", aggregatedResult);
            }

            return AgentResult.success(aggregatedResult,
                    Map.of("gameName", gameName,
                            "keywords", String.join(", ", keywords),
                            "resultCount", String.valueOf(allItems.size()),
                            "pagesFetched", String.valueOf(fetchedPages.size()),
                            "source", "web_search_multikey_ranked + AI_summary"));
        } catch (Exception e) {
            log.warn("RealtimeSearchAgent failed for project {}, trying cache", projectId, e);
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project != null) {
                Requirement requirement = requirementRepository.findByProjectId(projectId).orElse(null);
                String requirementContext = requirement != null ? requirement.getSummaryContent() : "";
                String cached = cacheManager.getCachedResult(project.getGameName(), requirementContext);
                if (cached == null) {
                    cached = cacheManager.getCachedResult(project.getGameName()); // 向后兼容回退
                }
                if (cached != null) {
                    sseEmitterService.sendProgress(projectId,
                            SseEventDTO.completed("SEARCH", "使用缓存搜索结果"));
                    return AgentResult.success(cached, Map.of("source", "cache"));
                }
            }
            return AgentResult.failure("实时搜索失败: " + e.getMessage());
        }
    }

    /**
     * 使用 AI 动态生成搜索关键词.
     */
    private List<String> generateSearchKeywords(String gameName, String requirementContext) {
        try {
            String prompt;
            if (requirementContext != null && !requirementContext.isBlank()) {
                prompt = String.format("""
                        你是一个搜索引擎优化专家。请根据以下游戏信息和用户需求，生成 5-8 个适合联网搜索的关键词。
                        
                        【游戏名称】%s
                        【用户需求上下文】%s
                        
                        生成规则：
                        1. 关键词要多样化，覆盖剧情、角色、版本更新、活动、攻略、社区讨论等维度
                        2. 每个关键词用中文，10-25字，直接可作为搜索框输入
                        3. 优先包含具体时间词（如"2026"、"最新"、"近期"）
                        4. 格式：一行一个关键词，不要编号，不要任何其他文字
                        """, gameName, limitLen(requirementContext, 1500));
            } else {
                prompt = String.format("""
                        你是一个搜索引擎优化专家。请根据以下游戏名称，生成 5-8 个适合联网搜索的关键词。
                        
                        【游戏名称】%s
                        
                        生成规则：
                        1. 关键词要多样化，覆盖剧情、角色、版本更新、活动、攻略、社区讨论等维度
                        2. 每个关键词用中文，10-25字，直接可作为搜索框输入
                        3. 优先包含具体时间词（如"2026"、"最新"、"近期"）
                        4. 格式：一行一个关键词，不要编号，不要任何其他文字
                        """, gameName);
            }

            List<ChatMessage> messages = List.of(
                    ChatMessage.system("你是一个搜索关键词生成器。只输出关键词，每行一个，不要任何解释或编号。"),
                    ChatMessage.user(prompt)
            );

            String response = deepSeekClient.chat(messages);
            if (response == null || response.isBlank()) {
                return defaultKeywords(gameName);
            }

            List<String> keywords = Arrays.stream(response.split("\\n"))
                    .map(line -> line.replaceAll("^\\d+[\\.\\)、]\\s*", "").trim())
                    .filter(line -> !line.isBlank() && line.length() >= 4)
                    .distinct()
                    .collect(Collectors.toList());

            if (keywords.isEmpty()) {
                return defaultKeywords(gameName);
            }

            if (keywords.size() > MAX_SEARCH_KEYWORDS) {
                keywords = keywords.subList(0, MAX_SEARCH_KEYWORDS);
            }

            return keywords;
        } catch (Exception e) {
            log.warn("AI keyword generation failed: {}", e.getMessage());
            return defaultKeywords(gameName);
        }
    }

    /**
     * 按 URL 去重，保留第一条出现的结果.
     */
    private List<WebSearchService.SearchResultItem> deduplicateByUrl(List<WebSearchService.SearchResultItem> items) {
        Set<String> seenUrls = new HashSet<>();
        List<WebSearchService.SearchResultItem> deduped = new ArrayList<>();
        for (WebSearchService.SearchResultItem item : items) {
            String url = item.url();
            if (url == null || url.isBlank() || seenUrls.add(url)) {
                deduped.add(item);
            }
        }
        return deduped;
    }

    /** 兜底关键词 */
    private List<String> defaultKeywords(String gameName) {
        return List.of(
                gameName + " 最新剧情 主线更新",
                gameName + " 新角色 人设 背景故事",
                gameName + " 最新版本 更新公告 活动",
                gameName + " 世界观 设定 故事背景",
                gameName + " 联动活动 最新动态"
        );
    }

    private String limitLen(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
