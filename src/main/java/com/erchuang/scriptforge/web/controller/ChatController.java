package com.erchuang.scriptforge.web.controller;

import com.erchuang.scriptforge.infra.ApiResponse;
import com.erchuang.scriptforge.model.entity.ChatMessage;
import com.erchuang.scriptforge.repository.ChatMessageRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天历史管理控制器.
 * <p>软删除模式：撤回标记 active=false，可恢复；清空才真删。</p>
 * <p>会话模式：每个项目下可有多个独立对话窗口（session），通过 sessionId 区分。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/chat")
public class ChatController {

    private final ChatMessageRepository chatMessageRepository;

    public ChatController(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * 加载项目聊天历史（仅活跃消息，支持按会话过滤）.
     */
    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> getHistory(@PathVariable Long projectId,
                                                              @RequestParam(required = false) Long sessionId) {
        List<ChatMessage> messages;
        if (sessionId != null) {
            messages = chatMessageRepository.findActiveBySession(projectId, sessionId);
        } else {
            // 未指定 sessionId 时，尝试加载最新会话；若无则加载旧数据
            Long maxSessionId = chatMessageRepository.findMaxSessionId(projectId);
            if (maxSessionId > 0) {
                messages = chatMessageRepository.findActiveBySession(projectId, maxSessionId);
            } else {
                messages = chatMessageRepository.findActiveLegacy(projectId);
            }
        }
        return ApiResponse.success(toDto(messages));
    }

    /**
     * 加载完整历史（含被撤回的，供历史面板展示）.
     */
    @GetMapping("/full-history")
    public ApiResponse<List<Map<String, Object>>> getFullHistory(@PathVariable Long projectId) {
        List<ChatMessage> messages = chatMessageRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        return ApiResponse.success(toDto(messages));
    }

    /**
     * 获取项目下所有会话列表（含摘要信息）.
     */
    @GetMapping("/sessions")
    public ApiResponse<List<Map<String, Object>>> getSessions(@PathVariable Long projectId) {
        List<Long> sessionIds = chatMessageRepository.findActiveSessionIds(projectId);
        // 也加载旧数据（sessionId=null）
        List<ChatMessage> legacyMessages = chatMessageRepository.findActiveLegacy(projectId);

        List<Map<String, Object>> sessions = new ArrayList<>();

        // 旧数据作为一个特殊会话
        if (!legacyMessages.isEmpty()) {
            Map<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("sessionId", null);
            legacy.put("isLegacy", true);
            String preview = "";
            for (ChatMessage m : legacyMessages) {
                if ("user".equals(m.getRole())) {
                    preview = m.getContent();
                    break;
                }
            }
            legacy.put("preview", preview != null && preview.length() > 50
                    ? preview.substring(0, 50) + "..." : preview);
            legacy.put("messageCount", legacyMessages.size());
            legacy.put("createdAt", legacyMessages.get(0).getCreatedAt() != null
                    ? legacyMessages.get(0).getCreatedAt().toString() : null);
            sessions.add(legacy);
        }

        for (Long sid : sessionIds) {
            List<ChatMessage> firstUserMsg = chatMessageRepository.findFirstUserMessageBySession(projectId, sid);
            long count = chatMessageRepository.countBySession(projectId, sid);

            Map<String, Object> session = new LinkedHashMap<>();
            session.put("sessionId", sid);
            session.put("isLegacy", false);

            String preview;
            String createdAt = null;
            if (!firstUserMsg.isEmpty()) {
                preview = firstUserMsg.get(0).getContent();
                if (firstUserMsg.get(0).getCreatedAt() != null) {
                    createdAt = firstUserMsg.get(0).getCreatedAt().toString();
                }
            } else {
                // 仅有 marker 的新会话：用 marker 创建时间，显示占位预览
                List<ChatMessage> firstMsg = chatMessageRepository.findFirstBySession(projectId, sid);
                preview = "[新会话]";
                if (!firstMsg.isEmpty() && firstMsg.get(0).getCreatedAt() != null) {
                    createdAt = firstMsg.get(0).getCreatedAt().toString();
                }
            }
            session.put("preview", preview.length() > 50
                    ? preview.substring(0, 50) + "..." : preview);
            session.put("messageCount", count);
            session.put("createdAt", createdAt);
            sessions.add(session);
        }
        return ApiResponse.success(sessions);
    }

    /**
     * 创建新会话 —— 为当前项目分配一个新的 sessionId，并立即持久化一条标记消息，确保会话在历史面板中可见.
     * 返回的 sessionId 可用于后续聊天消息标记.
     */
    @PostMapping("/new-session")
    @Transactional
    public ApiResponse<Map<String, Object>> newSession(@PathVariable Long projectId) {
        Long maxSessionId = chatMessageRepository.findMaxSessionId(projectId);
        long newSessionId = maxSessionId + 1;

        // 立即保存一条标记消息，让新会话出现在历史中
        ChatMessage marker = ChatMessage.builder()
                .projectId(projectId)
                .role("system")
                .content("[新会话]")
                .subType("session_start")
                .sessionId(newSessionId)
                .active(true)
                .build();
        chatMessageRepository.save(marker);

        return ApiResponse.success(Map.of(
                "sessionId", newSessionId,
                "message", "新会话已创建"
        ));
    }

    /**
     * 删除指定会话（软删除，标记该会话所有消息为 inactive）.
     */
    @DeleteMapping("/session/{sessionId}")
    @Transactional
    public ApiResponse<Map<String, Object>> deleteSession(@PathVariable Long projectId,
                                                           @PathVariable Long sessionId) {
        int count = chatMessageRepository.softDeleteBySession(projectId, sessionId);
        return ApiResponse.success(Map.of(
                "message", "已删除会话 " + sessionId + "，" + count + " 条消息",
                "deleted", count
        ));
    }

    /**
     * 撤回指定消息及其之后的所有对话（软删除）.
     * 传入 messageId 为要撤回的起点（通常是某条用户消息的 ID）.
     */
    @DeleteMapping("/undo/{messageId}")
    @Transactional
    public ApiResponse<Map<String, Object>> undoFromMessage(@PathVariable Long projectId,
                                                             @PathVariable Long messageId) {
        chatMessageRepository.softDeleteFrom(projectId, messageId);
        return ApiResponse.success(Map.of(
                "message", "已撤回",
                "undoneFromId", messageId
        ));
    }

    /**
     * 撤回最后一轮对话（便捷接口）.
     */
    @DeleteMapping("/undo-last")
    @Transactional
    public ApiResponse<Map<String, Object>> undoLast(@PathVariable Long projectId) {
        var active = chatMessageRepository.findActiveByProject(projectId);
        if (active.isEmpty()) {
            return ApiResponse.success(Map.of("message", "没有可撤回的消息"));
        }
        // 找到最后一条用户消息
        Long lastUserId = null;
        for (int i = active.size() - 1; i >= 0; i--) {
            if ("user".equals(active.get(i).getRole())) {
                lastUserId = active.get(i).getId();
                break;
            }
        }
        if (lastUserId == null) {
            return ApiResponse.success(Map.of("message", "没有可撤回的用户消息"));
        }
        chatMessageRepository.softDeleteFrom(projectId, lastUserId);
        return ApiResponse.success(Map.of("message", "已撤回最后一轮对话"));
    }

    /**
     * 恢复所有被撤回的消息.
     */
    @PostMapping("/restore")
    @Transactional
    public ApiResponse<Map<String, Object>> restore(@PathVariable Long projectId) {
        int count = chatMessageRepository.restoreAll(projectId);
        return ApiResponse.success(Map.of(
                "message", "已恢复 " + count + " 条消息",
                "restored", count
        ));
    }

    /**
     * 清空项目聊天记录（物理删除）.
     */
    @DeleteMapping("/clear")
    @Transactional
    public ApiResponse<Map<String, Object>> clearHistory(@PathVariable Long projectId) {
        chatMessageRepository.deleteByProjectId(projectId);
        return ApiResponse.success(Map.of("message", "聊天记录已清空"));
    }

    /**
     * 硬删除指定消息及其之后的所有消息（不可恢复，供历史面板使用）.
     */
    @DeleteMapping("/delete-from/{messageId}")
    @Transactional
    public ApiResponse<Map<String, Object>> hardDeleteFrom(@PathVariable Long projectId,
                                                            @PathVariable Long messageId) {
        int count = chatMessageRepository.hardDeleteFrom(projectId, messageId);
        return ApiResponse.success(Map.of(
                "message", "已永久删除 " + count + " 条消息",
                "deleted", count
        ));
    }

    // ==================== 工具方法 ====================

    private List<Map<String, Object>> toDto(List<ChatMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", msg.getId());
            item.put("role", msg.getRole());
            item.put("content", msg.getContent());
            item.put("subType", msg.getSubType());
            item.put("active", msg.getActive());
            item.put("createdAt", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
            result.add(item);
        }
        return result;
    }
}
