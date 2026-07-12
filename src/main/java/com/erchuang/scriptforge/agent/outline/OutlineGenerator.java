package com.erchuang.scriptforge.agent.outline;

import com.erchuang.scriptforge.llm.ContextType;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.llm.TokenCounter;
import com.erchuang.scriptforge.model.enums.ScopeLevel;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import com.erchuang.scriptforge.stream.StreamTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 多版大纲生成器——基于需求+人设+剧情背景，生成3版差异化大纲.
 *
 * @author ScriptForge Team
 */
public class OutlineGenerator {

    private static final Logger log = LoggerFactory.getLogger(OutlineGenerator.class);

    private final DeepSeekClient deepSeekClient;
    private final PromptTemplate promptTemplate;
    private final TokenCounter tokenCounter;

    /** 三版大纲的差异化方向 */
    private static final String[] DIRECTIONS = {
            "【方向一：情感驱动】以角色间的情感发展为主线，侧重内心戏和关系变化",
            "【方向二：冲突驱动】以外部冲突（战斗/阴谋/危机）为主轴，侧重动作和悬念",
            "【方向三：成长驱动】以角色成长与自我探索为核心，侧重人物弧光和蜕变"
    };

    public OutlineGenerator(DeepSeekClient deepSeekClient, PromptTemplate promptTemplate,
                             TokenCounter tokenCounter) {
        this.deepSeekClient = deepSeekClient;
        this.promptTemplate = promptTemplate;
        this.tokenCounter = tokenCounter;
    }

    /**
     * 生成3版差异化大纲（流式推送到前端）.
     *
     * @param projectId          项目ID（用于流式追踪）
     * @param requirementSummary 需求摘要
     * @param characterCards     角色人设
     * @param searchResult       剧情搜索结果
     * @param style              写作风格
     * @param scope              篇幅级别
     * @return 3版大纲文本列表
     */
    public List<String> generate(Long projectId, String requirementSummary, String characterCards,
                                  String searchResult, WritingStyle style, ScopeLevel scope) {
        List<String> outlines = new ArrayList<>();

        for (int i = 0; i < DIRECTIONS.length; i++) {
            log.info("Generating outline version {}", i + 1);

            String versionLabel = String.format("v%d/%d", i + 1, DIRECTIONS.length);
            StreamTracker.updateStep(projectId, "OUTLINE_DESIGN",
                    "\n\n---\n\n### 大纲方案 - " + versionLabel + "（" +
                    (i == 0 ? "情感驱动" : i == 1 ? "冲突驱动" : "成长驱动") + "）\n\n", -1);

            String systemPrompt = promptTemplate.load("outline-system");
            String userPrompt = buildOutlinePrompt(i, requirementSummary, characterCards,
                    searchResult, style, scope);

            // Token估算和截断
            if (tokenCounter.mayExceedLimit(userPrompt)) {
                userPrompt = tokenCounter.truncateToTokenLimit(userPrompt,
                        tokenCounter.getMaxTokensPerRequest() / 2, ContextType.NARRATIVE);
            }

            // 流式调用
            StringBuilder outlineBuilder = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            final int round = i + 1;
            deepSeekClient.chatStream(List.of(
                    DeepSeekClient.ChatMessage.system(systemPrompt),
                    DeepSeekClient.ChatMessage.user(userPrompt)
            ))
            .doOnNext(chunk -> {
                outlineBuilder.append(chunk);
                StreamTracker.updateStep(projectId, "OUTLINE_DESIGN", chunk, -1);
            })
            .doOnComplete(latch::countDown)
            .doOnError(e -> {
                log.warn("Outline stream error for version {}: {}", round, e.getMessage());
                latch.countDown();
            })
            .subscribe();

            try { latch.await(120, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            outlines.add(outlineBuilder.toString());
        }

        return outlines;
    }

    private String buildOutlinePrompt(int version, String requirementSummary, String characterCards,
                                       String searchResult, WritingStyle style, ScopeLevel scope) {
        int chapterCount = scope != null ? scope.getMinChapters() : 5;

        return String.format("""
                请根据以下信息，生成一版差异化的大纲（版本 %d/3）：
                
                ## 差异化方向
                %s
                
                ## 需求摘要
                %s
                
                ## 角色人设
                %s
                
                ## 剧情背景
                %s
                
                ## 参数要求
                - 写作风格: %s
                - 篇幅级别: %s（不少于%d章）
                
                请以Markdown格式输出大纲，包含以下部分：
                1. 标题
                2. 故事梗概（200-300字）
                3. 核心冲突
                4. 情感走向
                5. 章节划分（每章含标题和摘要）
                6. 分支点设计（2-3个关键剧情分支点，每个分支点包含：触发章节、分支标题、2-3个选项及对应的后续章节摘要）
                """,
                version + 1,
                DIRECTIONS[version],
                requirementSummary,
                characterCards,
                searchResult != null ? searchResult : "（无缓存剧情数据）",
                style.getDisplayName(),
                scope.getDisplayName(),
                chapterCount
        );
    }
}
