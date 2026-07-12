package com.erchuang.scriptforge.ws;

import com.erchuang.scriptforge.model.dto.SseEventDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket 会话管理器 —— 替代原 SseEmitterService，基于 WebSocket 实现双向实时通信.
 *
 * <p>每一组 WebSocket 连接按 projectId 分组管理，支持同一项目多客户端（多标签页）同时接收推送。</p>
 *
 * @author ScriptForge Team
 */
@Component
public class WsSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WsSessionManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** projectId → 该项目的所有 WebSocket 会话集合 */
    private final Map<Long, Set<WebSocketSession>> projectSessions = new ConcurrentHashMap<>();

    // ==================== 连接管理 ====================

    /**
     * 注册 WebSocket 会话到指定项目.
     */
    public void register(Long projectId, WebSocketSession session) {
        projectSessions.computeIfAbsent(projectId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.debug("WebSocket session {} registered for project {}", session.getId(), projectId);
    }

    /**
     * 移除 WebSocket 会话（连接关闭时调用）.
     */
    public void remove(Long projectId, WebSocketSession session) {
        Set<WebSocketSession> sessions = projectSessions.get(projectId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                projectSessions.remove(projectId);
            }
        }
        log.debug("WebSocket session {} removed from project {}", session.getId(), projectId);
    }

    /**
     * 关闭指定项目的所有 WebSocket 连接（删除项目时调用）.
     */
    public void closeProject(Long projectId) {
        Set<WebSocketSession> sessions = projectSessions.remove(projectId);
        if (sessions != null) {
            for (WebSocketSession session : sessions) {
                try {
                    session.close(CloseStatus.NORMAL);
                } catch (IOException ignored) {
                }
            }
            log.debug("Closed {} WebSocket sessions for project {}", sessions.size(), projectId);
        }
    }

    /**
     * 获取当前活跃连接数.
     */
    public int getActiveConnectionCount() {
        return projectSessions.values().stream().mapToInt(Set::size).sum();
    }

    // ==================== 消息推送（保留原 SseEmitterService 的 API） ====================

    /**
     * 推送进度事件.
     */
    public void sendProgress(Long projectId, SseEventDTO event) {
        sendToProject(projectId, "progress", event);
    }

    /**
     * 推送完成事件.
     */
    public void sendComplete(Long projectId, String message) {
        sendToProject(projectId, "complete", new SimpleMessage(message));
    }

    /**
     * 推送错误事件.
     */
    public void sendError(Long projectId, String error) {
        sendToProject(projectId, "error", new SimpleMessage(error));
    }

    /**
     * 推送提问事件.
     */
    public void sendQuestion(Long projectId, String jsonData) {
        sendRawToProject(projectId, "question", jsonData);
    }

    /**
     * 推送自定义事件（供 StreamTracker 使用）.
     */
    public void sendEvent(Long projectId, String eventName, String data) {
        sendRawToProject(projectId, eventName, data);
    }

    /**
     * 推送流式内容块.
     */
    public void sendContentChunk(Long projectId, String step, String chunk) {
        var payload = Map.of("step", step, "chunk", chunk);
        sendToProject(projectId, "content", payload);
    }

    // ==================== 内部实现 ====================

    private void sendToProject(Long projectId, String eventType, Object payload) {
        Set<WebSocketSession> sessions = projectSessions.get(projectId);
        if (sessions == null || sessions.isEmpty()) {
            log.trace("No WebSocket sessions for project {}, skipping event {}", projectId, eventType);
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(new WsMessage(eventType, MAPPER.writeValueAsString(payload)));
            broadcast(sessions, json);
        } catch (Exception e) {
            log.warn("Failed to serialize WebSocket message for project {}: {}", projectId, e.getMessage());
        }
    }

    private void sendRawToProject(Long projectId, String eventType, String rawJson) {
        Set<WebSocketSession> sessions = projectSessions.get(projectId);
        if (sessions == null || sessions.isEmpty()) {
            log.trace("No WebSocket sessions for project {}, skipping event {}", projectId, eventType);
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(new WsMessage(eventType, rawJson));
            broadcast(sessions, json);
        } catch (Exception e) {
            log.warn("Failed to serialize WebSocket message for project {}: {}", projectId, e.getMessage());
        }
    }

    private void broadcast(Set<WebSocketSession> sessions, String message) {
        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    synchronized (session) {
                        session.sendMessage(textMessage);
                    }
                } catch (IOException e) {
                    log.debug("Failed to send to session {}: {}", session.getId(), e.getMessage());
                    removeFromAll(session);
                }
            }
        }
    }

    /**
     * 从所有项目中移除已断开的会话.
     */
    private void removeFromAll(WebSocketSession session) {
        projectSessions.forEach((pid, sessions) -> {
            if (sessions.remove(session) && sessions.isEmpty()) {
                projectSessions.remove(pid);
            }
        });
    }

    // ==================== 消息格式 ====================

    /** WebSocket 消息载体. */
    private record WsMessage(String event, String data) {}

    /** 简单文本消息. */
    private record SimpleMessage(String message) {}
}
