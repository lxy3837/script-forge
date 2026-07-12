package com.erchuang.scriptforge.stream;

import com.erchuang.scriptforge.infra.SseEmitterService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局流式跟踪工具——3个核心 API，内部复用 SseEmitterService 已有连接.
 *
 * <pre>
 *   StreamTracker.startStep(projectId, "req", "需求调研");
 *   StreamTracker.updateStep(projectId, "req", "分析中...", 30);
 *   StreamTracker.endStep(projectId, "req", "completed", 100);
 * </pre>
 */
@Component
public class StreamTracker {

    private static final Logger log = LoggerFactory.getLogger(StreamTracker.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static SseEmitterService sseService;
    private static final AtomicInteger counter = new AtomicInteger(0);

    public StreamTracker(SseEmitterService sseEmitterService) {
        sseService = sseEmitterService;
        log.info("StreamTracker initialized, sseService={}", sseService != null ? "ready" : "NULL");
    }

    @PostConstruct
    void verify() {
        log.info("StreamTracker @PostConstruct: sseService={}", sseService != null ? "ready" : "NULL");
    }

    // ---------- 3 个核心 API ----------

    public static void startStep(Long projectId, String stepId, String title) {
        send(projectId, StreamEvent.start(stepId, title));
    }

    public static void updateStep(Long projectId, String stepId, String content, int progress) {
        send(projectId, StreamEvent.update(stepId, content, progress));
    }

    public static void endStep(Long projectId, String stepId, String status, int progress) {
        send(projectId, StreamEvent.end(stepId, status, progress));
    }

    // ---------- 内部 ----------

    private static void send(Long projectId, StreamEvent event) {
        if (sseService == null) {
            log.warn("StreamTracker: SseEmitterService not available (sseService is null)");
            return;
        }
        try {
            int n = counter.incrementAndGet();
            String json = mapper.writeValueAsString(event);
            // 每次都打印日志（调试阶段）
            log.info("StreamTracker send #{} projectId={} type={} stepId={} contentLen={}",
                    n, projectId, event.type(), event.stepId(),
                    event.content() != null ? event.content().length() : 0);
            sseService.sendEvent(projectId, "track", json);
        } catch (JsonProcessingException e) {
            log.error("StreamTracker serialize error: {}", e.getMessage());
        }
    }
}
