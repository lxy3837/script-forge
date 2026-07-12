package com.erchuang.scriptforge.agent.review;

import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 节奏评估器——分析场景密度、情绪起伏、叙事张力.
 *
 * @author ScriptForge Team
 */
public class PacingAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PacingAnalyzer.class);

    private final DeepSeekClient deepSeekClient;
    private final PromptTemplate promptTemplate;

    public PacingAnalyzer(DeepSeekClient deepSeekClient, PromptTemplate promptTemplate) {
        this.deepSeekClient = deepSeekClient;
        this.promptTemplate = promptTemplate;
    }

    /**
     * 分析剧本节奏.
     *
     * @param scriptContent 剧本内容
     * @param chapterCount  章节总数
     * @return 节奏分析结果
     */
    public String analyze(String scriptContent, int chapterCount) {
        try {
            String systemPrompt = promptTemplate.load("review-system");

            String userPrompt = String.format(
                    "直接分析以下剧本的叙事节奏（共%d章），评估场景密度、情绪起伏、叙事张力：\n\n%s\n\n" +
                    "直接以JSON格式输出分析结果，禁止任何引导语。包含：整体节奏评分、各章节节奏分布、高潮点位置、节奏问题及建议。",
                    chapterCount, scriptContent);

            String result = deepSeekClient.chat(List.of(
                    DeepSeekClient.ChatMessage.system(systemPrompt),
                    DeepSeekClient.ChatMessage.user(userPrompt)
            ));

            return result;
        } catch (Exception e) {
            log.warn("Pacing analysis failed: {}", e.getMessage());
            return "节奏分析执行异常，请人工审核。";
        }
    }
}
