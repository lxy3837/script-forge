package com.erchuang.scriptforge.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Spring WebSocket 配置 —— 注册项目实时通信的 WebSocket 端点.
 *
 * <p>替代原有的 SseController (/api/sse/projects/{id})，
 * 前端改为连接 {@code ws://host/ws/projects/{projectId}}。</p>
 *
 * @author ScriptForge Team
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ProjectWebSocketHandler handler;

    public WebSocketConfig(ProjectWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/projects/{projectId}")
                .setAllowedOrigins("*");
    }
}
