package com.erchuang.scriptforge.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE事��数据传输对象，用于实时向Web前端推送进度.
 *
 * @author ScriptForge Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SseEventDTO {

    /** 当前步骤名称 */
    private String step;

    /** 执行状态：RUNNING / COMPLETED / FAILED */
    private String status;

    /** 进度描述消息 */
    private String message;

    /** 进度百分比（0-100） */
    private Integer progress;

    /** 附加数据（JSON） */
    private String data;

    /**
     * 创建运行中的进度事件.
     */
    public static SseEventDTO running(String step, String message, int progress) {
        return SseEventDTO.builder()
                .step(step)
                .status("RUNNING")
                .message(message)
                .progress(progress)
                .build();
    }

    /**
     * 创建提问事件——Agent需要用户输入.
     */
    public static SseEventDTO question(String questionId, String question) {
        return SseEventDTO.builder()
                .step("QUESTION")
                .status("QUESTION")
                .message(question)
                .data(questionId)
                .progress(null)
                .build();
    }

    /**
     * 创建完成事件.
     */
    public static SseEventDTO completed(String step, String message) {
        return SseEventDTO.builder()
                .step(step)
                .status("COMPLETED")
                .message(message)
                .progress(100)
                .build();
    }

    /**
     * 创建失败事件.
     */
    public static SseEventDTO failed(String step, String message) {
        return SseEventDTO.builder()
                .step(step)
                .status("FAILED")
                .message(message)
                .progress(0)
                .build();
    }
}
