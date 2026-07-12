package com.erchuang.scriptforge.agent.question;

import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.model.dto.SseEventDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 提问服务 — AI Agent 通过此服务向用户发送问题并等待回答.
 * <p>
 * 支持两种模式：
 * <ul>
 *   <li>纯文本提问：用户自由输入</li>
 *   <li>结构化提问：带预定义选项（类似 AskUserQuestion 工具）</li>
 * </ul>
 * 使用 CompletableFuture 在 Agent 线程中阻塞等待用户的 HTTP 响应。
 * </p>
 *
 * @author ScriptForge Team
 */
@Service
public class QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);
    private static final long QUESTION_TIMEOUT_SECONDS = 600;

    private final SseEmitterService sseEmitterService;
    private final ObjectMapper objectMapper;

    /** 挂起中的问题: projectId -> (questionId -> CompletableFuture) */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, CompletableFuture<String>>> pendingQuestions = new ConcurrentHashMap<>();

    public QuestionService(SseEmitterService sseEmitterService, ObjectMapper objectMapper) {
        this.sseEmitterService = sseEmitterService;
        this.objectMapper = objectMapper;
    }

    /**
     * 纯文本提问（无预定义选项）.
     */
    public String askQuestion(Long projectId, String question) {
        return askQuestion(projectId, question, null, false);
    }

    /**
     * 带预定义选项的结构化提问.
     *
     * @param projectId   项目ID
     * @param question    问题内容
     * @param options     预定义选项列表（null 表示自由输入）
     * @param multiSelect 是否允许多选
     * @return 用户的回答
     */
    public String askQuestion(Long projectId, String question,
                               List<QuestionData.QuestionOption> options,
                               boolean multiSelect) {
        String questionId = UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<String> future = new CompletableFuture<>();

        pendingQuestions.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>())
                       .put(questionId, future);

        log.info("Asking project {}: {}", projectId, question);

        // 构建结构化提问数据
        QuestionData qData = new QuestionData(questionId, question, options, multiSelect);

        try {
            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("QUESTION", "Agent 正在等待你的回答...", -1));

            String json = objectMapper.writeValueAsString(qData);
            sseEmitterService.sendQuestion(projectId, json);
        } catch (Exception e) {
            log.warn("Failed to send question via SSE: {}", e.getMessage());
        }

        try {
            String answer = future.get(QUESTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Got answer for project {}: {}", projectId, answer);
            return answer;
        } catch (TimeoutException e) {
            log.warn("Question timeout for project {}", projectId);
            return "(超时未回答)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Question interrupted for project {}", projectId);
            return "(已取消)";
        } catch (ExecutionException e) {
            log.error("Question error for project {}: {}", projectId, e.getMessage());
            return "(回答出错)";
        } finally {
            clearQuestion(projectId, questionId);
        }
    }

    /**
     * 用户提交回答——由 Controller 调用.
     */
    public void submitAnswer(Long projectId, String questionId, String answer) {
        var map = pendingQuestions.get(projectId);
        if (map != null) {
            CompletableFuture<String> future = map.get(questionId);
            if (future != null && !future.isDone()) {
                future.complete(answer != null ? answer : "");
                log.info("Answer submitted for project {} question {}: {}", projectId, questionId, answer);
            }
        }
    }

    /**
     * 检查指定项目是否有挂起的问题.
     */
    public boolean hasPendingQuestion(Long projectId) {
        var map = pendingQuestions.get(projectId);
        return map != null && !map.isEmpty();
    }

    private void clearQuestion(Long projectId, String questionId) {
        var map = pendingQuestions.get(projectId);
        if (map != null) {
            map.remove(questionId);
            if (map.isEmpty()) {
                pendingQuestions.remove(projectId);
            }
        }
    }
}
