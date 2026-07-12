package com.erchuang.scriptforge.agent.character;

import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.DeepSeekClient.ChatMessage;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.model.entity.CharacterCard;
import com.erchuang.scriptforge.model.entity.Requirement;
import com.erchuang.scriptforge.stream.StreamTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * OOC冲突检测器——交叉比对剧本内容与角色人设，检测OOC风险并生成报告.
 *
 * @author ScriptForge Team
 */
@Component
public class ConflictDetector {

    private static final Logger log = LoggerFactory.getLogger(ConflictDetector.class);

    /**
     * 检测角色人设与需求之间的潜在冲突（流式版本，逐 chunk 推送前端）.
     */
    public String detectAndStream(Long projectId, List<CharacterCard> characterCards,
                                   Requirement requirement,
                                   DeepSeekClient deepSeekClient, PromptTemplate promptTemplate) {
        if (characterCards.isEmpty()) {
            return "";
        }

        try {
            String systemPrompt = promptTemplate.load("character-system");

            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("请分析以下角色的人设与创作需求之间是否存在潜在的OOC（角色偏离）风险：\n\n");
            userPrompt.append("## 创作需求\n");
            userPrompt.append(requirement.getSummaryContent()).append("\n\n");
            userPrompt.append("## 角色人设\n");

            for (CharacterCard card : characterCards) {
                userPrompt.append("### ").append(card.getName()).append("\n");
                if (card.getPersonality() != null) {
                    userPrompt.append("- 性格: ").append(card.getPersonality()).append("\n");
                }
                if (card.getBackground() != null) {
                    userPrompt.append("- 背景: ").append(card.getBackground()).append("\n");
                }
                userPrompt.append("\n");
            }

            userPrompt.append("直接以JSON格式返回冲突检测结果，禁止任何引导语。包含冲突类型、涉及角色、偏离程度和修改建议。");

            StringBuilder result = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            deepSeekClient.chatStream(List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt.toString())
            ))
            .doOnNext(chunk -> {
                result.append(chunk);
                StreamTracker.updateStep(projectId, "character", chunk, -1);
            })
            .doOnComplete(latch::countDown)
            .doOnError(e -> {
                log.warn("Conflict detection stream error: {}", e.getMessage());
                latch.countDown();
            })
            .subscribe();

            try { latch.await(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

            return result.toString();
        } catch (Exception e) {
            log.warn("Conflict detection failed, returning empty report: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 检测角色人设与需求之间的潜在冲突（非流式版本，向后兼容）.
     */
    public String detect(List<CharacterCard> characterCards, Requirement requirement,
                          DeepSeekClient deepSeekClient, PromptTemplate promptTemplate) {
        if (characterCards.isEmpty()) {
            return "";
        }

        try {
            String systemPrompt = promptTemplate.load("character-system");

            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("请分析以下角色的人设与创作需求之间是否存在潜在的OOC（角色偏离）风险：\n\n");
            userPrompt.append("## 创作需求\n");
            userPrompt.append(requirement.getSummaryContent()).append("\n\n");
            userPrompt.append("## 角色人设\n");

            for (CharacterCard card : characterCards) {
                userPrompt.append("### ").append(card.getName()).append("\n");
                if (card.getPersonality() != null) {
                    userPrompt.append("- 性格: ").append(card.getPersonality()).append("\n");
                }
                if (card.getBackground() != null) {
                    userPrompt.append("- 背景: ").append(card.getBackground()).append("\n");
                }
                userPrompt.append("\n");
            }

            userPrompt.append("请以JSON格式返回冲突检测结果，包含冲突类型、涉及角色、偏离程度和修改建议。");

            String aiResponse = deepSeekClient.chat(List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt.toString())
            ));

            return aiResponse;
        } catch (Exception e) {
            log.warn("Conflict detection failed, returning empty report: {}", e.getMessage());
            return "";
        }
    }
}
