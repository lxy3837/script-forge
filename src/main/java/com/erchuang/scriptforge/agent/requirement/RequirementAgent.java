package com.erchuang.scriptforge.agent.requirement;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.agent.question.QuestionAgent;
import com.erchuang.scriptforge.agent.question.QuestionData.QuestionOption;
import com.erchuang.scriptforge.agent.search.WebSearchService;
import com.erchuang.scriptforge.infra.WorkspaceFileWriter;
import com.erchuang.scriptforge.stream.StreamTracker;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.DeepSeekClient.ChatMessage;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.entity.Requirement;
import com.erchuang.scriptforge.model.enums.ScopeLevel;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.RequirementRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 需求调研Agent——分析用户模糊输入，识别缺失维度，生成结构化追问与需求摘要.
 *
 * @author ScriptForge Team
 */
@Component
public class RequirementAgent {

    private static final Logger log = LoggerFactory.getLogger(RequirementAgent.class);

    private final ProjectRepository projectRepository;
    private final RequirementRepository requirementRepository;
    private final DeepSeekClient deepSeekClient;
    private final PromptTemplate promptTemplate;
    private final WebSearchService searchService;
    private final ObjectMapper objectMapper;
    private final QuestionAgent questionAgent;
    private final QuestionGenerator questionGenerator;
    private final WorkspaceFileWriter workspaceFileWriter;

    public RequirementAgent(ProjectRepository projectRepository,
                             RequirementRepository requirementRepository,
                             DeepSeekClient deepSeekClient,
                             PromptTemplate promptTemplate,
                             WebSearchService searchService,
                             ObjectMapper objectMapper,
                             QuestionAgent questionAgent,
                             WorkspaceFileWriter workspaceFileWriter) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.deepSeekClient = deepSeekClient;
        this.promptTemplate = promptTemplate;
        this.searchService = searchService;
        this.objectMapper = objectMapper;
        this.questionAgent = questionAgent;
        this.workspaceFileWriter = workspaceFileWriter;
        this.questionGenerator = new QuestionGenerator(deepSeekClient, promptTemplate, objectMapper);
    }

    /**
     * 执行需求调研——从项目标题中提取信息并生成需求摘要.
     *
     * @param projectId 项目ID
     * @return Agent执行结果
     */
    public AgentResult execute(Long projectId) {
        return executeWithSupplementary(projectId, null);
    }

    /**
     * 带补充信息的需求调研（用户确认循环中使用）.
     *
     * @param projectId    项目ID
     * @param supplementary 用户补充信息，null 表示无补充
     * @return Agent执行结果
     */
    public AgentResult executeWithSupplementary(Long projectId, String supplementary) {
        long startTime = System.currentTimeMillis();
        log.info("RequirementAgent started for project {} (supplementary={})",
                projectId, supplementary != null);

        try {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

            StreamTracker.startStep(projectId, "requirement", "需求调研");

            // 如果有补充信息，先显示
            if (supplementary != null && !supplementary.isBlank()) {
                StreamTracker.updateStep(projectId, "requirement",
                        "根据你的补充信息重新分析...\n\n补充: " + supplementary + "\n\n", 0);
            }

            StreamTracker.updateStep(projectId, "requirement",
                    "正在联网搜索【" + project.getGameName() + "】背景信息...\n\n", 2);

            // 联网搜索游戏背景（异步搜索，带降级）
            String searchResult = "";
            try {
                searchResult = searchService.search(project.getGameName() + " 世界观 角色 剧情设定");
                log.info("Requirement web search got {} chars", searchResult.length());
            } catch (Exception ex) {
                log.warn("Requirement web search failed, continuing without: {}", ex.getMessage());
            }

            StreamTracker.updateStep(projectId, "requirement", "正在分析需求...\n\n", 5);

            // 构建需求分析prompt
            String systemPrompt = promptTemplate.load("requirement-system");
            String searchContext = searchResult != null && !searchResult.isBlank()
                    && !searchResult.startsWith("关于 \"")
                    ? "\n\n【联网搜索参考信息】\n" + searchResult + "\n"
                    : "";

            String userPrompt = "请分析以下用户创意需求，生成结构化的需求摘要：\n\n" +
                    "项目标题: " + project.getTitle() + "\n" +
                    "目标游戏: " + project.getGameName() + searchContext +
                    (supplementary != null && !supplementary.isBlank()
                            ? "\n\n【用户补充/修改要求】\n" + supplementary + "\n\n请结合上述补充要求重新进行分析。" : "") +
                    "\n\n" +
                    "请识别并补全：目标角色、世界观设定、风格偏好、篇幅要求。";

            List<ChatMessage> messages = List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)
            );

            // 流式调用 DeepSeek，逐 chunk 推送到前端
            StringBuilder sb = new StringBuilder();
            deepSeekClient.chatStream(messages)
                    .doOnNext(chunk -> {
                        sb.append(chunk);
                        StreamTracker.updateStep(projectId, "requirement", chunk, -1);
                    })
                    .blockLast();
            String aiResponse = sb.toString();

            StreamTracker.updateStep(projectId, "requirement", "\n\n---\n\n正在生成需求摘要...\n\n", 60);

            // 生成结构化需求摘要
            RequirementSummaryBuilder builder = new RequirementSummaryBuilder();
            String summary = builder.buildSummary(project.getTitle(), project.getGameName(), aiResponse);

            // 构建ConversationContext（简化为单轮）
            ConversationContext context = new ConversationContext();
            context.addRound("用户创意输入", userPrompt, aiResponse);

            // 尝试解析AI响应中的结构化信息
            WritingStyle style = parseWritingStyle(aiResponse);
            ScopeLevel scope = parseScopeLevel(aiResponse);
            String characters = parseTargetCharacters(aiResponse);

            // 初次分析：让 AI 生成针对性提问
            if (supplementary == null) {
                String collectedAnswers = askAiGeneratedQuestions(projectId, summary, context);
                if (collectedAnswers != null && !collectedAnswers.isBlank()) {
                    log.info("Got user answers from AI questions, re-analyzing with: {}",
                            collectedAnswers.substring(0, Math.min(100, collectedAnswers.length())));
                    return executeWithSupplementary(projectId, collectedAnswers);
                }
            }

            // 保存或更新需求摘要（避免重复插入违反唯一约束）
            var existing = requirementRepository.findByProjectId(projectId);
            Requirement requirement;
            if (existing.isPresent()) {
                requirement = existing.get();
                requirement.setSummaryContent(summary);
                requirement.setConversationHistory(objectMapper.writeValueAsString(context.getRounds()));
                requirement.setTargetCharacters(characters);
                requirement.setStylePreference(style);
                requirement.setScopeLevel(scope);
            } else {
                requirement = Requirement.builder()
                        .project(project)
                        .summaryContent(summary)
                        .conversationHistory(objectMapper.writeValueAsString(context.getRounds()))
                        .targetCharacters(characters)
                        .stylePreference(style)
                        .scopeLevel(scope)
                        .build();
            }
            requirementRepository.save(requirement);

            // 写入工作空间文件
            String projectTitle = project.getTitle();
            workspaceFileWriter.write(projectId, "需求分析.md",
                    "# " + projectTitle + " - 需求分析\n\n" +
                    summary + "\n\n" +
                    "## 目标角色\n" + (characters != null ? characters : "未指定") + "\n");

            long duration = System.currentTimeMillis() - startTime;
            log.info("RequirementAgent completed for project {} in {}ms", projectId, duration);

            StreamTracker.endStep(projectId, "requirement", "completed", 100);

            return AgentResult.success(summary, Map.of("characters", characters != null ? characters : ""));
        } catch (Exception e) {
            log.error("RequirementAgent failed for project {}: {}", projectId, e.getMessage(), e);
            return AgentResult.failure("需求调研失败: " + e.getMessage());
        }
    }

    /**
     * 让 AI 根据已有分析结果生成针对性提问，逐题收集用户答案.
     *
     * @return 收集到的所有答案（Q&A格式），无问题时返回 null
     */
    private String askAiGeneratedQuestions(Long projectId, String summary, ConversationContext context) {
        StreamTracker.updateStep(projectId, "requirement",
                "\n\n---\n\nAI 正在分析需要追问的信息...\n\n", 62);

        List<QuestionGenerator.Question> questions;
        try {
            questions = questionGenerator.generateQuestions(summary, context);
        } catch (Exception e) {
            log.warn("Failed to generate AI questions: {}", e.getMessage());
            return null;
        }

        if (questions.isEmpty()) {
            log.info("AI decided no questions needed for project {}", projectId);
            return null;
        }

        log.info("AI generated {} questions for project {}", questions.size(), projectId);
        StreamTracker.updateStep(projectId, "requirement",
                "\n\n---\n\n请回答以下 " + questions.size() + " 个问题来完善需求...\n\n", 63);

        StringBuilder answers = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            QuestionGenerator.Question q = questions.get(i);
            String answer;

            if (q.options() != null && !q.options().isEmpty()) {
                List<QuestionOption> opts = q.options().stream()
                        .map(QuestionOption::of)
                        .collect(Collectors.toList());
                boolean multi = "multi_choice".equals(q.type());
                answer = questionAgent.ask(projectId, q.question(), opts, multi);
            } else {
                answer = questionAgent.ask(projectId, q.question());
            }

            if (answer != null && !answer.isBlank() && !answer.startsWith("(")) {
                answers.append("Q").append(i + 1).append(": ").append(q.question()).append("\n");
                answers.append("A: ").append(answer).append("\n\n");
            }
        }

        return answers.isEmpty() ? null : answers.toString();
    }

    private WritingStyle parseWritingStyle(String aiResponse) {
        if (aiResponse == null) return WritingStyle.LIGHT_NOVEL;
        String lower = aiResponse.toLowerCase();
        if (lower.contains("戏剧")) return WritingStyle.DRAMA;
        if (lower.contains("小说")) return WritingStyle.NOVEL;
        if (lower.contains("脚本") || lower.contains("台词")) return WritingStyle.SCRIPT;
        return WritingStyle.LIGHT_NOVEL;
    }

    private ScopeLevel parseScopeLevel(String aiResponse) {
        if (aiResponse == null) return ScopeLevel.MEDIUM;
        if (aiResponse.contains("长篇") || aiResponse.toLowerCase().contains("long")) return ScopeLevel.LONG;
        if (aiResponse.contains("短篇") || aiResponse.toLowerCase().contains("short")) return ScopeLevel.SHORT;
        return ScopeLevel.MEDIUM;
    }

    private String parseTargetCharacters(String aiResponse) {
        // 简化实现：提取角色名称
        if (aiResponse == null) return "[]";
        try {
            // 尝试从AI响应中提取角色列表
            List<String> characters = new java.util.ArrayList<>();
            for (String line : aiResponse.split("\n")) {
                if (line.contains("角色") && (line.contains("：") || line.contains(":"))) {
                    String[] parts = line.split("[：:]", 2);
                    if (parts.length > 1) {
                        characters.add(parts[1].trim());
                    }
                }
            }
            return objectMapper.writeValueAsString(characters);
        } catch (Exception e) {
            return "[]";
        }
    }
}
