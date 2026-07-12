package com.erchuang.scriptforge.web.controller;

import com.erchuang.scriptforge.agent.question.QuestionService;
import com.erchuang.scriptforge.infra.ApiResponse;
import org.springframework.web.bind.annotation.*;

/**
 * 提问回答接口 —— 用户通过此接口提交对 Agent 问题的回答.
 *
 * @author ScriptForge Team
 */
@RestController
@RequestMapping("/api/projects")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    /**
     * 提交问题的回答.
     */
    @PostMapping("/{projectId}/answer")
    public ApiResponse<String> submitAnswer(
            @PathVariable Long projectId,
            @RequestParam String questionId,
            @RequestParam String answer) {
        questionService.submitAnswer(projectId, questionId, answer);
        return ApiResponse.success("回答已提交", "Agent 将继续执行");
    }
}
