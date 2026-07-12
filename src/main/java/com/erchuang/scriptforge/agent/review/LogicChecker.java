package com.erchuang.scriptforge.agent.review;

import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 逻辑一致性检查器——检查情节连贯性、时间线合理性.
 *
 * @author ScriptForge Team
 */
public class LogicChecker {

    private static final Logger log = LoggerFactory.getLogger(LogicChecker.class);

    private final DeepSeekClient deepSeekClient;
    private final PromptTemplate promptTemplate;

    public LogicChecker(DeepSeekClient deepSeekClient, PromptTemplate promptTemplate) {
        this.deepSeekClient = deepSeekClient;
        this.promptTemplate = promptTemplate;
    }

    /**
     * 检查剧本逻辑一致性.
     *
     * @param scriptContent 剧本内容
     * @return 逻辑检查结果
     */
    public String check(String scriptContent) {
        try {
            String systemPrompt = promptTemplate.load("review-system");

            String userPrompt = "直接检查以下剧本的逻辑一致性，包括情节连贯性、时间线合理性、角色行为动机等：\n\n" +
                    scriptContent + "\n\n" +
                    "直接以JSON格式列出所有逻辑问题，禁止任何引导语。包含：问题描述、严重等级、涉及章节、修改建议。";

            String result = deepSeekClient.chat(List.of(
                    DeepSeekClient.ChatMessage.system(systemPrompt),
                    DeepSeekClient.ChatMessage.user(userPrompt)
            ));

            return result;
        } catch (Exception e) {
            log.warn("Logic check failed: {}", e.getMessage());
            return "逻辑检查执行异常，请人工审核。";
        }
    }
}
