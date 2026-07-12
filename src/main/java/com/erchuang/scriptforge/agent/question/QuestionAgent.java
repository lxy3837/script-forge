package com.erchuang.scriptforge.agent.question;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 提问Agent — 当 AI 需要向用户询问信息时调用.
 * <p>
 * 类似 IDE 中的 AskUserQuestion 工具，支持两种模式：
 * <ul>
 *   <li>纯文本提问：用户自由输入</li>
 *   <li>结构化提问：带预定义选项按钮（单选/多选）</li>
 * </ul>
 * 典型使用方式：
 * <pre>
 * // 纯文本
 * String answer = questionAgent.ask(projectId, "需要补充什么信息？");
 *
 * // 带选项（单选）
 * var options = List.of(
 *     QuestionOption.of("爽文风格", "节奏快、冲突强"),
 *     QuestionOption.of("正剧风格", "逻辑严谨、感情细腻"));
 * String choice = questionAgent.ask(projectId, "请选择剧本风格", options, false);
 *
 * // 多选
 * String ids = questionAgent.ask(projectId, "你想保留哪些章节？", chapterOptions, true);
 * </pre>
 *
 * @author ScriptForge Team
 */
@Component
public class QuestionAgent {

    private static final Logger log = LoggerFactory.getLogger(QuestionAgent.class);

    private final QuestionService questionService;

    public QuestionAgent(QuestionService questionService) {
        this.questionService = questionService;
    }

    /**
     * 纯文本提问（无预定义选项）.
     *
     * @param projectId 项目ID
     * @param question  问题内容
     * @return 用户的回答
     */
    public String ask(Long projectId, String question) {
        log.info("QuestionAgent asking project {}: {}", projectId, question);
        return questionService.askQuestion(projectId, question);
    }

    /**
     * 带预定义选项的结构化提问（类似 AskUserQuestion）.
     *
     * @param projectId   项目ID
     * @param question    问题内容
     * @param options     可选选项列表
     * @param multiSelect 是否允许多选
     * @return 用户的回答（单选返回选项label，多选返回逗号分隔字符串，自由输入返回原始文本）
     */
    public String ask(Long projectId, String question,
                      List<QuestionData.QuestionOption> options,
                      boolean multiSelect) {
        log.info("QuestionAgent asking project {} [{} options, multi={}]: {}",
                projectId, options != null ? options.size() : 0, multiSelect, question);
        return questionService.askQuestion(projectId, question, options, multiSelect);
    }

    /**
     * 检查是否有挂起的问题.
     */
    public boolean hasPendingQuestion(Long projectId) {
        return questionService.hasPendingQuestion(projectId);
    }
}
