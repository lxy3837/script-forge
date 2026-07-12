package com.erchuang.scriptforge.infra;

import com.erchuang.scriptforge.model.dto.SseEventDTO;
import com.erchuang.scriptforge.ws.WsSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 实时通信服务 —— 原 SSE 实现，现已委托给 {@link WsSessionManager}（WebSocket 后端）.
 *
 * <p>保留此类的 API 以维持向后兼容，所有组件（Agent、Orchestrator、Service）无需修改 import。</p>
 *
 * @author ScriptForge Team
 */
@Service
public class SseEmitterService {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterService.class);

    private final WsSessionManager ws;

    public SseEmitterService(WsSessionManager ws) {
        this.ws = ws;
    }

    /**
     * 向指定项目推送任意事件（供 StreamTracker 使用）.
     */
    public void sendEvent(Long projectId, String eventName, String data) {
        log.debug("SseEmitterService.sendEvent → WebSocket: projectId={} event={}", projectId, eventName);
        ws.sendEvent(projectId, eventName, data);
    }

    /**
     * 向指定项目推送流式内容块.
     */
    public void sendContentChunk(Long projectId, String step, String chunk) {
        ws.sendContentChunk(projectId, step, chunk);
    }

    /**
     * 向指定项目推送进度事件.
     */
    public void sendProgress(Long projectId, SseEventDTO event) {
        ws.sendProgress(projectId, event);
    }

    /**
     * 向指定项目推送完成事件.
     */
    public void sendComplete(Long projectId, String message) {
        ws.sendComplete(projectId, message);
    }

    /**
     * 向指定项目推送错误事件.
     */
    public void sendError(Long projectId, String error) {
        ws.sendError(projectId, error);
    }

    /**
     * 向指定项目推送提问事件.
     */
    public void sendQuestion(Long projectId, String jsonData) {
        ws.sendQuestion(projectId, jsonData);
    }

    /**
     * 关闭指定项目的实时通信连接.
     */
    public void closeEmitter(Long projectId) {
        ws.closeProject(projectId);
    }

    /**
     * 获取当前活跃连接数.
     */
    public int getActiveConnectionCount() {
        return ws.getActiveConnectionCount();
    }

    /**
     * 已废弃 —— WebSocket 连接不再需要手动创建.
     * 前端直接通过 ws:// 协议连接即可。
     */
    @Deprecated
    public Object createEmitter(Long projectId) {
        log.warn("createEmitter() is deprecated with WebSocket; connections are auto-managed.");
        return null;
    }
}
