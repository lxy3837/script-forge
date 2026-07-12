package com.erchuang.scriptforge.agent.script;

import com.erchuang.scriptforge.stream.StreamTracker;
import com.erchuang.scriptforge.llm.ContextType;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.DeepSeekClient.StreamChunk;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.llm.TokenCounter;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单章剧本生成器——逐章调用DeepSeek生成分镜内容.
 *
 * @author ScriptForge Team
 */
public class ChapterGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChapterGenerator.class);

    private final DeepSeekClient deepSeekClient;
    private final PromptTemplate promptTemplate;
    private final TokenCounter tokenCounter;
    private final StyleAdapter styleAdapter;

    /** 每章最少场景数 */
    private static final int MIN_SCENES_PER_CHAPTER = 3;
    /** 单章生成超时（秒），比全局超时短很多 */
    private static final int CHAPTER_TIMEOUT_SECONDS = 180;

    public ChapterGenerator(DeepSeekClient deepSeekClient, PromptTemplate promptTemplate,
                             TokenCounter tokenCounter) {
        this.deepSeekClient = deepSeekClient;
        this.promptTemplate = promptTemplate;
        this.tokenCounter = tokenCounter;
        this.styleAdapter = new StyleAdapter();
    }

    /**
     * 生成单章分镜剧本.
     *
     * @param chapterNumber    章节序号（从1开始）
     * @param chapterTitle     章节标题（来自大纲）
     * @param chapterAbstract  章节摘要（来自大纲，必须严格遵守）
     * @param fullOutlineCtx   完整大纲上下文（梗概+冲突+情感+章节划分）
     * @param previousContent  前文内容（用于保持连贯性）
     * @param characterCards   角色人设
     * @param searchResult     联网搜索结果/剧情背景资料
     * @param style            写作风格
     * @return 章节内容包含标题、场景列表、原始文本
     */
    public ChapterResult generateChapter(int chapterNumber, String chapterTitle,
                                          String chapterAbstract, String fullOutlineCtx,
                                          String previousContent,
                                          String characterCards, String searchResult,
                                          WritingStyle style,
                                          Long projectId) {
        log.info("Generating chapter {}: {}", chapterNumber, chapterTitle);

        String systemPrompt = promptTemplate.load("script-system");
        String stylePrompt = styleAdapter.getStylePrompt(style);

        String userPrompt = String.format("""
                ## 本章的创作依据（你必须严格基于以下内容创作，不得偏离）

                章节标题：%s

                章节摘要（本章必须实现的剧情）：
                %s

                ## 整体大纲背景

                %s

                ## 写作风格
                %s

                ## 角色信息
                %s

                ## 参考资料
                %s

                ## 前文回顾
                %s

                ## 要求
                - **必须严格遵循上述"章节摘要"中的剧情走向创作，不得偏离或自行发挥**
                - 本章至少包含%d个场景
                - 每个场景需包含：场景描述、角色动作、台词对白
                - 保持与前文的连贯性
                - 使用指定的写作风格
                """,
                chapterTitle, chapterAbstract, fullOutlineCtx,
                stylePrompt, characterCards,
                (searchResult != null && !searchResult.isBlank()) ? searchResult : "（无额外参考资料）",
                previousTimeOrEmpty(previousContent),
                MIN_SCENES_PER_CHAPTER
        );

        if (tokenCounter.mayExceedLimit(userPrompt)) {
            userPrompt = tokenCounter.truncateToTokenLimit(userPrompt,
                    tokenCounter.getMaxTokensPerRequest() / 3, ContextType.NARRATIVE);
        }

        // 流式调用（分离 reasoning 和 content），逐 chunk 推送纯剧本内容
        String stepKey = "script_" + chapterNumber;
        StreamTracker.startStep(projectId, stepKey, "第" + chapterNumber + "章: " + chapterTitle);
        StringBuilder contentBuffer = new StringBuilder();
        StringBuilder reasoningBuffer = new StringBuilder();
        try {
            deepSeekClient.chatStreamWithReasoning(List.of(
                    DeepSeekClient.ChatMessage.system(systemPrompt),
                    DeepSeekClient.ChatMessage.user(userPrompt)
            ), CHAPTER_TIMEOUT_SECONDS)
            .doOnNext(chunk -> {
                if (chunk.isReasoning()) {
                    reasoningBuffer.append(chunk.reasoning());
                }
                if (chunk.effectiveText() != null && !chunk.effectiveText().isEmpty()) {
                    contentBuffer.append(chunk.effectiveText());
                    // 只将纯剧本内容推送到前端
                    StreamTracker.updateStep(projectId, stepKey, chunk.effectiveText(), -1);
                }
            })
            .blockLast();
        } catch (Exception e) {
            log.error("Chapter {} generation failed: {}", chapterNumber, e.getMessage());
            StreamTracker.endStep(projectId, stepKey, "failed", 0);
            throw new RuntimeException("第" + chapterNumber + "章生成失败: " + e.getMessage(), e);
        }
        String rawContent = contentBuffer.toString();
        String reasoning = reasoningBuffer.toString();
        StreamTracker.endStep(projectId, stepKey, "completed", 100);

        int sceneCount = countScenes(rawContent);

        return new ChapterResult(chapterNumber, chapterTitle, rawContent, reasoning, sceneCount);
    }

    private int countScenes(String rawContent) {
        if (rawContent == null) return MIN_SCENES_PER_CHAPTER;
        int count = 0;
        for (String line : rawContent.split("\n")) {
            if (line.trim().matches("^#{1,3}\\s*(场景|Scene|第.+场)")) {
                count++;
            }
        }
        return Math.max(count, MIN_SCENES_PER_CHAPTER);
    }

    private static String previousTimeOrEmpty(String prev) {
        return (prev != null && !prev.isEmpty()) ? prev : "（第一章，无前文）";
    }

    /**
     * 章节生成结果封装.
     */
    public record ChapterResult(int chapterNumber, String title, String rawContent, String reasoning, int sceneCount) {
    }
}
