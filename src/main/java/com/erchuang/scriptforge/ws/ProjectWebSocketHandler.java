package com.erchuang.scriptforge.ws;

import com.erchuang.scriptforge.agent.chat.ScriptForgeAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目 WebSocket 处理器 —— 统一处理实时推送和聊天消息.
 *
 * <p>连接路径: {@code ws://host/ws/projects/{projectId}}</p>
 * <p>前端发来的消息支持以下类型:
 * <ul>
 *   <li>{@code {"type":"chat","message":"..."}} — ScriptForge Agent 聊天消息</li>
 *   <li>其他消息仅作心跳检测</li>
 * </ul>
 * </p>
 *
 * @author ScriptForge Team
 */
@Component
public class ProjectWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ProjectWebSocketHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WsSessionManager sessionManager;
    private final ScriptForgeAgent scriptForgeAgent;

    /** 每个项目最近一次接收聊天消息的时间戳，用于速率限制 */
    private final ConcurrentHashMap<Long, Long> lastChatTsMap = new ConcurrentHashMap<>();
    /** 聊天消息最小间隔（毫秒）：同项目两次消息至少间隔此值 */
    private static final long CHAT_MIN_INTERVAL_MS = 1000;

    public ProjectWebSocketHandler(WsSessionManager sessionManager,
                                   ScriptForgeAgent scriptForgeAgent) {
        this.sessionManager = sessionManager;
        this.scriptForgeAgent = scriptForgeAgent;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long projectId = extractProjectId(session);
        if (projectId == null) {
            log.warn("Rejected WebSocket connection without valid projectId in path: {}", session.getUri());
            try {
                session.close(CloseStatus.BAD_DATA);
            } catch (Exception ignored) {
            }
            return;
        }
        sessionManager.register(projectId, session);
        log.info("WebSocket connected: session={}, projectId={}", session.getId(), projectId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        Long projectId = extractProjectId(session);
        if (projectId == null) return;

        try {
            JsonNode msg = MAPPER.readTree(payload);
            String type = msg.has("type") ? msg.get("type").asText() : "";
            if ("chat".equals(type) && msg.has("message")) {
                // 速率限制：同项目聊天消息至少间隔 1 秒
                long now = System.currentTimeMillis();
                Long lastTs = lastChatTsMap.get(projectId);
                if (lastTs != null && (now - lastTs) < CHAT_MIN_INTERVAL_MS) {
                    return; // 丢弃过快消息
                }
                lastChatTsMap.put(projectId, now);

                String userMessage = msg.get("message").asText();
                Long sessionId = msg.has("sessionId") && !msg.get("sessionId").isNull()
                        ? msg.get("sessionId").asLong() : null;
                log.info("Chat message from project {} (session {}): {}", projectId, sessionId, userMessage);
                scriptForgeAgent.handleChatMessage(projectId, sessionId, userMessage);
            }
        } catch (Exception e) {
            log.trace("Non-JSON or unknown WebSocket message from session {}: {}", session.getId(), payload);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long projectId = extractProjectId(session);
        if (projectId != null) {
            sessionManager.remove(projectId, session);
        }
        log.debug("WebSocket disconnected: session={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket transport error for session {}: {}", session.getId(),
                exception != null ? exception.getMessage() : "null");
        Long projectId = extractProjectId(session);
        if (projectId != null) {
            sessionManager.remove(projectId, session);
        }
    }

    /**
     * 从请求路径中提取 projectId. 路径格式: /ws/projects/{projectId}
     */
    private Long extractProjectId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        String path = uri.getPath();
        // /ws/projects/123
        int idx = path.lastIndexOf('/');
        if (idx < 0) return null;
        try {
            return Long.parseLong(path.substring(idx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
