package com.erchuang.scriptforge.agent.requirement;

import java.util.*;

/**
 * 多轮对话上下文管理——记录每轮Q&A并提供结构化追问生成的基础数据.
 *
 * @author ScriptForge Team
 */
public class ConversationContext {

    /** 对话轮次列表 */
    private final List<Map<String, String>> rounds = new ArrayList<>();

    /** 最大对话轮次 */
    private static final int MAX_ROUNDS = 3;

    /**
     * 添加一轮对话.
     *
     * @param question 问题（AI提出的追问）
     * @param userAnswer 用户回答
     * @param aiResponse AI对此的回答响应
     */
    public void addRound(String question, String userAnswer, String aiResponse) {
        if (rounds.size() >= MAX_ROUNDS) {
            rounds.remove(0);
        }
        Map<String, String> round = new LinkedHashMap<>();
        round.put("question", question != null ? question : "");
        round.put("userAnswer", userAnswer != null ? userAnswer : "");
        round.put("aiResponse", aiResponse != null ? aiResponse : "");
        rounds.add(round);
    }

    /**
     * 获取所有对话轮次.
     */
    public List<Map<String, String>> getRounds() {
        return Collections.unmodifiableList(rounds);
    }

    /**
     * 获取对话轮次数量.
     */
    public int getRoundCount() {
        return rounds.size();
    }

    /**
     * 是否已达最大对话轮次.
     */
    public boolean isMaxRoundsReached() {
        return rounds.size() >= MAX_ROUNDS;
    }

    /**
     * 将对话记录转换为prompt可用的文本格式.
     */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rounds.size(); i++) {
            Map<String, String> round = rounds.get(i);
            sb.append("【第").append(i + 1).append("轮】\n");
            sb.append("AI提问: ").append(round.get("question")).append("\n");
            sb.append("用户回答: ").append(round.get("userAnswer")).append("\n");
            sb.append("AI响应: ").append(round.get("aiResponse")).append("\n\n");
        }
        return sb.toString();
    }
}
