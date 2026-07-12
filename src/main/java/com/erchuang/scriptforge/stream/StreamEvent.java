package com.erchuang.scriptforge.stream;

/**
 * 流式跟踪事件模型——通用抽象，不依赖任何特定 LLM 或 Agent 框架.
 */
public record StreamEvent(
        String type,       // STEP_START | STEP_UPDATE | STEP_END
        String stepId,     // 步骤唯一标识
        String title,      // 步骤标题（STEP_START 时必填）
        String content,    // 内容（STEP_UPDATE 时为增量内容）
        String status,     // 状态（STEP_END 时必填：completed | failed | cancelled）
        int progress,      // 进度百分比 0-100
        long timestamp     // 事件时间戳
) {
    public static StreamEvent start(String stepId, String title) {
        return new StreamEvent("STEP_START", stepId, title, "", "", 0, System.currentTimeMillis());
    }

    public static StreamEvent update(String stepId, String content, int progress) {
        return new StreamEvent("STEP_UPDATE", stepId, "", content, "", progress, System.currentTimeMillis());
    }

    public static StreamEvent end(String stepId, String status, int progress) {
        return new StreamEvent("STEP_END", stepId, "", "", status, progress, System.currentTimeMillis());
    }
}
