package com.erchuang.scriptforge.web.controller;

import com.erchuang.scriptforge.agent.orchestrator.WorkflowOrchestrator;
import com.erchuang.scriptforge.infra.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * 工作流控制 REST 接口——启动/控制项目工作流执行.
 *
 * @author ScriptForge Team
 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final WorkflowOrchestrator orchestrator;

    public WorkflowController(WorkflowOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 启动项目工作流（异步执行）.
     */
    @PostMapping("/projects/{projectId}/start")
    public ApiResponse<String> startWorkflow(@PathVariable Long projectId) {
        // 异步执行工作流，不阻塞HTTP响应
        CompletableFuture.runAsync(() -> orchestrator.startWorkflow(projectId));
        return ApiResponse.success("工作流已启动", "项目 " + projectId + " 的工作流正在后台执行");
    }

    /**
     * 暂停项目工作流.
     */
    @PostMapping("/projects/{projectId}/cancel")
    public ApiResponse<String> cancelWorkflow(@PathVariable Long projectId) {
        orchestrator.cancelWorkflow(projectId);
        return ApiResponse.success("工作流暂停信号已发送", "项目 " + projectId + " 将在完成当前步骤后暂停");
    }
}
