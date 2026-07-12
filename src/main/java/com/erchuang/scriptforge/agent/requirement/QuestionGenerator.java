package com.erchuang.scriptforge.agent.requirement;

import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化追问生成器——根据缺失维度生成单选/多选/填空问题.
 *
 * @author ScriptForge Team
 */
public class QuestionGenerator {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerator.class);

    private final DeepSeekClient deepSeekClient;
    private final PromptTemplate promptTemplate;
    private final ObjectMapper objectMapper;

    /** 需求调研覆盖的维度 */
    private static final String[] DIMENSIONS = {
            "目标角色", "世界观设定", "故事背景", "风格偏好", "篇幅要求"
    };

    public QuestionGenerator(DeepSeekClient deepSeekClient, PromptTemplate promptTemplate,
                              ObjectMapper objectMapper) {
        this.deepSeekClient = deepSeekClient;
        this.promptTemplate = promptTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成结构化追问问题列表.
     *
     * @param userInput 用户输入（需求摘要文本）
     * @param context   对话上下文
     * @return 问题列表（每个问题含问题文本、类型、选项）
     */
    public List<Question> generateQuestions(String userInput, ConversationContext context) {
        String prompt = buildQuestionPrompt(userInput, context);
        String aiResponse = deepSeekClient.chat(List.of(
                DeepSeekClient.ChatMessage.user(prompt)
        ));

        return parseQuestions(aiResponse);
    }

    private String buildQuestionPrompt(String userInput, ConversationContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("基于以下用户创意需求分析，找出缺失或模糊的信息维度，生成结构化追问问题。\n\n");
        sb.append("需求分析内容:\n").append(userInput).append("\n\n");

        if (context.getRoundCount() > 0) {
            sb.append("已有对话记录:\n");
            sb.append(context.toPromptText());
        }

        sb.append("\n请严格以纯JSON数组格式输出，不要包含markdown代码块标记：\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"dimension\": \"目标角色\",\n");
        sb.append("    \"question\": \"你想让故事围绕哪些角色展开？\",\n");
        sb.append("    \"type\": \"multi_choice\",\n");
        sb.append("    \"options\": [\"主角A\", \"主角B\", \"反派X\"]\n");
        sb.append("  },\n");
        sb.append("  {\n");
        sb.append("    \"dimension\": \"风格偏好\",\n");
        sb.append("    \"question\": \"你偏好什么写作风格？\",\n");
        sb.append("    \"type\": \"single_choice\",\n");
        sb.append("    \"options\": [\"轻小说\", \"正剧\", \"同人\"]\n");
        sb.append("  }\n");
        sb.append("]\n\n");
        sb.append("type 只能是 single_choice / multi_choice / fill_blank 之一。\n");
        sb.append("fill_blank 类型不需要 options 字段。\n");
        sb.append("只输出有追问价值的问题（信息已明确则跳过），最多5个。");

        return sb.toString();
    }

    /**
     * 解析AI返回的JSON问题列表——从响应中提取JSON数组，兼容markdown包裹.
     */
    private List<Question> parseQuestions(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) {
            log.warn("AI returned empty question response, using defaults");
            return generateDefaultQuestions();
        }

        try {
            // 尝试从响应中提取JSON数组（处理markdown代码块）
            String json = aiResponse.trim();
            int arrayStart = json.indexOf('[');
            int arrayEnd = json.lastIndexOf(']');
            if (arrayStart >= 0 && arrayEnd > arrayStart) {
                json = json.substring(arrayStart, arrayEnd + 1);
            }

            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                log.warn("AI question response is not a JSON array, using defaults");
                return generateDefaultQuestions();
            }

            List<Question> questions = new ArrayList<>();
            for (JsonNode node : root) {
                String dimension = node.has("dimension") ? node.get("dimension").asText() : "";
                String questionText = node.has("question") ? node.get("question").asText() : "";
                String type = node.has("type") ? node.get("type").asText() : "fill_blank";

                List<String> options = new ArrayList<>();
                JsonNode opts = node.get("options");
                if (opts != null && opts.isArray()) {
                    for (JsonNode opt : opts) {
                        options.add(opt.asText());
                    }
                }

                if (!questionText.isBlank()) {
                    questions.add(new Question(dimension, questionText, type, options));
                }
            }

            if (questions.isEmpty()) {
                log.warn("No valid questions parsed from AI response, using defaults");
                return generateDefaultQuestions();
            }

            log.info("Successfully parsed {} AI-generated questions", questions.size());
            return questions;
        } catch (Exception e) {
            log.warn("Failed to parse AI question response: {}, using defaults", e.getMessage());
            return generateDefaultQuestions();
        }
    }

    private List<Question> generateDefaultQuestions() {
        List<Question> questions = new ArrayList<>();
        questions.add(new Question("目标角色", "请确认故事涉及的主要角色有哪些？",
                "multi_choice", List.of("角色A", "角色B", "角色C")));
        questions.add(new Question("风格偏好", "你偏好什么写作风格？",
                "single_choice", List.of("轻小说", "戏剧", "小说体", "脚本/台词体")));
        questions.add(new Question("篇幅要求", "你期望的剧本篇幅是？",
                "single_choice", List.of("短篇（3-5章）", "中篇（5-10章）", "长篇（10-20章）")));
        return questions;
    }

    /**
     * 问题封装.
     */
    public record Question(String dimension, String question, String type, List<String> options) {
    }
}
