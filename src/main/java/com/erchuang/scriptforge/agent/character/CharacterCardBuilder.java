package com.erchuang.scriptforge.agent.character;

import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.model.entity.CharacterCard;

import java.util.List;
import java.util.Map;

/**
 * 人设卡片组装器——将检索到的角色信息组合为完整的人设卡片文本.
 *
 * @author ScriptForge Team
 */
public class CharacterCardBuilder {

    private final DeepSeekClient deepSeekClient;
    private final PromptTemplate promptTemplate;

    public CharacterCardBuilder(DeepSeekClient deepSeekClient, PromptTemplate promptTemplate) {
        this.deepSeekClient = deepSeekClient;
        this.promptTemplate = promptTemplate;
    }

    /**
     * 构建人设卡片文本.
     *
     * @param cards    角色卡片列表
     * @param gameName 游戏名称
     * @return 格式化的卡片文本（Markdown格式）
     */
    public String buildCards(List<CharacterCard> cards, String gameName) {
        if (cards == null || cards.isEmpty()) {
            return "# 角色人设信息\n\n*暂无角色人设数据，请先构建知识库。*\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 《").append(gameName).append("》角色人设卡\n\n");

        for (int i = 0; i < cards.size(); i++) {
            CharacterCard card = cards.get(i);
            sb.append("## ").append(i + 1).append(". ").append(card.getName()).append("\n\n");

            if (card.getPersonality() != null) {
                sb.append("### 性格特征\n").append(card.getPersonality()).append("\n\n");
            }
            if (card.getAppearance() != null) {
                sb.append("### 外貌描述\n").append(card.getAppearance()).append("\n\n");
            }
            if (card.getBackground() != null) {
                sb.append("### 背景故事\n").append(card.getBackground()).append("\n\n");
            }
            if (card.getRelationships() != null) {
                sb.append("### 人际关系\n").append(card.getRelationships()).append("\n\n");
            }
            if (card.getClassicQuotes() != null) {
                sb.append("### 经典台词\n").append(card.getClassicQuotes()).append("\n\n");
            }
            sb.append("---\n\n");
        }

        return sb.toString();
    }
}
