package com.erchuang.scriptforge.agent.orchestrator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Agent执行结果封装——统一各Agent的返回值格式.
 *
 * @author ScriptForge Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentResult {

    /** 是否成功 */
    private boolean success;

    /** 结果数据（JSON字符串或序列化对象） */
    private String data;

    /** 附加元数据 */
    private Map<String, Object> metadata;

    /** 错误信息（失败时填充） */
    private String errorMessage;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /**
     * 创建成功结果.
     */
    public static AgentResult success(String data) {
        return AgentResult.builder()
                .success(true)
                .data(data)
                .durationMs(0)
                .build();
    }

    /**
     * 创建成功结果（带元数据）.
     */
    public static AgentResult success(String data, Map<String, Object> metadata) {
        return AgentResult.builder()
                .success(true)
                .data(data)
                .metadata(metadata)
                .durationMs(0)
                .build();
    }

    /**
     * 创建失败结果.
     */
    public static AgentResult failure(String errorMessage) {
        return AgentResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .durationMs(0)
                .build();
    }
}
