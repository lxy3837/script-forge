package com.erchuang.scriptforge.llm;

import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek API对话客户端——基于Spring WebClient的非阻塞HTTP客户端.
 * <p>
 * 支持普通模式和SSE流式模式调用DeepSeek Chat Completion API。
 * 端点: /v1/chat/completions
 * </p>
 *
 * @author ScriptForge Team
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${deepseek.chat.model}")
    private String model;

    @Value("${deepseek.chat.max-tokens}")
    private int maxTokens;

    @Value("${deepseek.chat.temperature}")
    private double temperature;

    @Value("${deepseek.chat.timeout-seconds}")
    private int timeoutSeconds;

    public DeepSeekClient(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式块——区分 reasoning 和 content.
     */
    public record StreamChunk(String content, String reasoning) {
        public boolean isReasoning() { return reasoning != null && !reasoning.isEmpty(); }
        public String effectiveText() {
            if (content != null && !content.isEmpty()) return content;
            return reasoning != null ? reasoning : "";
        }
    }

    // ----- 内部类：ChatMessage / ToolDef / ToolCallResult -----

    /**
     * 工具定义（用于 function calling）.
     */
    public record ToolDef(String name, String description, Map<String, Object> parameters) {}

    /**
     * LLM 返回的工具调用请求.
     */
    public record ToolCallRequest(String id, String name, String arguments) {}

    /**
     * 工具调用结果.
     */
    public record ToolCallResult(String toolCallId, String content) {}

    // ----- 内部类：ChatMessage -----

    /**
     * 聊天消息封装.
     */
    public record ChatMessage(String role, String content) {
        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }
    }

    // ----- 对话方法 -----

    /**
     * 调用DeepSeek Chat API（普通模式，返回完整响应）.
     *
     * @param messages 消息列表
     * @return AI响应文本
     */
    public String chat(List<ChatMessage> messages) {
        return chat(messages, this.maxTokens, this.temperature);
    }

    /**
     * 调用DeepSeek Chat API（普通模式，支持工具调用）.
     * <p>返回的 ChatToolResponse 中包含文本内容或工具调用请求列表.</p>
     *
     * @param messages 消息列表
     * @param tools    可用工具定义列表
     * @return 响应（content 或 toolCalls 二选一非空）
     */
    public ChatToolResponse chatWithTools(List<ChatMessage> messages, List<ToolDef> tools) {
        return chatWithTools(messages, tools, this.maxTokens, this.temperature);
    }

    /**
     * 调用DeepSeek Chat API（普通模式，自定义参数，支持工具调用）.
     */
    public ChatToolResponse chatWithTools(List<ChatMessage> messages, List<ToolDef> tools,
                                           int maxTokens, double temperature) {
        log.debug("Calling DeepSeek Chat API (tools={}) with {} messages", tools.size(), messages.size());

        var requestBody = new java.util.LinkedHashMap<String, Object>();
        requestBody.put("model", this.model);
        requestBody.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", (Object) m.content()))
                .toList());
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);
        requestBody.put("stream", false);

        // 添加工具定义
        List<Map<String, Object>> toolDefs = tools.stream().map(t -> {
            Map<String, Object> func = new java.util.LinkedHashMap<>();
            func.put("name", t.name());
            func.put("description", t.description());
            func.put("parameters", t.parameters());
            return Map.<String, Object>of("type", "function", "function", func);
        }).toList();
        requestBody.put("tools", toolDefs);

        try {
            String response = webClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(new BusinessException(
                                            ErrorCode.DEEPSEEK_API_ERROR,
                                            "DeepSeek API error: " + clientResponse.statusCode() + " - " + body))))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            return extractToolResponse(response);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.DEEPSEEK_API_ERROR,
                    "Failed to call DeepSeek API with tools: " + e.getMessage(), e);
        }
    }

    /**
     * 工具调用聊天响应.
     */
    public record ChatToolResponse(String content, List<ToolCallRequest> toolCalls) {}

    /**
     * 调用DeepSeek Chat API（普通模式，自定义参数）.
     *
     * @param messages    消息列表
     * @param maxTokens   最大Token数
     * @param temperature 温度参数
     * @return AI响应文本
     */
    public String chat(List<ChatMessage> messages, int maxTokens, double temperature) {
        log.debug("Calling DeepSeek Chat API with {} messages", messages.size());

        Map<String, Object> requestBody = buildRequestBody(messages, maxTokens, temperature, false);

        try {
            String response = webClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(new BusinessException(
                                            ErrorCode.DEEPSEEK_API_ERROR,
                                            "DeepSeek API error: " + clientResponse.statusCode() + " - " + body))))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            return extractContent(response);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.DEEPSEEK_API_ERROR,
                    "Failed to call DeepSeek API: " + e.getMessage(), e);
        }
    }

    /**
     * 调用DeepSeek Chat API（SSE流式模式）.
     *
     * @param messages 消息列表
     * @return 流式响应文本的Flux
     */
    public Flux<String> chatStream(List<ChatMessage> messages) {
        return chatStream(messages, this.timeoutSeconds);
    }

    /**
     * 调用DeepSeek Chat API（SSE流式模式，自定义超时）.
     *
     * @param messages     消息列表
     * @param timeoutSecs  超时秒数
     * @return 流式响应文本的Flux
     */
    public Flux<String> chatStream(List<ChatMessage> messages, int timeoutSecs) {
        return chatStream(messages, this.maxTokens, this.temperature, timeoutSecs);
    }

    /**
     * 调用DeepSeek Chat API（SSE流式模式，自定义参数）.
     *
     * @param messages    消息列表
     * @param maxTokens   最大Token数
     * @param temperature 温度参数
     * @return 流式响应文本的Flux
     */
    public Flux<String> chatStream(List<ChatMessage> messages, int maxTokens, double temperature) {
        return chatStream(messages, maxTokens, temperature, this.timeoutSeconds);
    }

    /**
     * 调用DeepSeek Chat API（SSE流式模式，全部自定义参数）.
     */
    public Flux<String> chatStream(List<ChatMessage> messages, int maxTokens, double temperature, int timeoutSecs) {
        log.debug("Calling DeepSeek Chat API (stream) with {} messages", messages.size());

        Map<String, Object> requestBody = buildRequestBody(messages, maxTokens, temperature, true);

        return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchangeToFlux(response -> {
                    if (response.statusCode().is4xxClientError() || response.statusCode().is5xxServerError()) {
                        return response.bodyToMono(String.class)
                                .flatMapMany(body -> Flux.error(new BusinessException(
                                        ErrorCode.DEEPSEEK_API_ERROR,
                                        "DeepSeek API stream error: " + response.statusCode() + " - " + body)));
                    }
                    // 用 String.class 让 Netty 自动处理 UTF-8 多字节边界
                    return response.bodyToFlux(String.class);
                })
                .flatMap(chunk -> Flux.fromArray(chunk.split("\n")))
                .doOnNext(line -> log.debug("DeepSeek stream raw line: {}", line))
                .map(line -> {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) return trimmed;
                    // 跳过 SSE 注释（心跳）
                    if (trimmed.startsWith(":")) return "";
                    // 剥离 SSE data: 前缀（可能已由 String 解码器剥离，做防御检查）
                    if (trimmed.startsWith("data: ")) {
                        trimmed = trimmed.substring(6).trim();
                    } else if (trimmed.startsWith("data:")) {
                        trimmed = trimmed.substring(5).trim();
                    }
                    return trimmed;
                })
                .filter(line -> !line.isEmpty() && !"[DONE]".equals(line))
                .mapNotNull(this::extractStreamContent)
                .timeout(Duration.ofSeconds(timeoutSecs))
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) {
                        return e;
                    }
                    return new BusinessException(ErrorCode.DEEPSEEK_API_ERROR,
                            "Failed to stream from DeepSeek API: " + e.getMessage(), e);
                });
    }

    // ----- 内部辅助方法 -----

    private Map<String, Object> buildRequestBody(List<ChatMessage> messages, int maxTokens,
                                                  double temperature, boolean stream) {
        List<Map<String, String>> msgs = messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        return Map.of(
                "model", this.model,
                "messages", msgs,
                "max_tokens", maxTokens,
                "temperature", temperature,
                "stream", stream
        );
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null) {
                    JsonNode content = message.get("content");
                    if (content != null && !content.isNull()) {
                        return content.asText();
                    }
                }
            }
            throw new BusinessException(ErrorCode.DEEPSEEK_API_ERROR,
                    "Unexpected response format from DeepSeek API");
        } catch (Exception e) {
            if (e instanceof BusinessException) {
                throw (BusinessException) e;
            }
            throw new BusinessException(ErrorCode.DEEPSEEK_API_ERROR,
                    "Failed to parse DeepSeek API response: " + e.getMessage(), e);
        }
    }

    private ChatToolResponse extractToolResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0) {
                throw new BusinessException(ErrorCode.DEEPSEEK_API_ERROR,
                        "Unexpected response format from DeepSeek API");
            }
            JsonNode message = choices.get(0).get("message");
            if (message == null) {
                throw new BusinessException(ErrorCode.DEEPSEEK_API_ERROR,
                        "No message in DeepSeek response");
            }

            // 检查 tool_calls
            JsonNode toolCallsNode = message.get("tool_calls");
            if (toolCallsNode != null && toolCallsNode.isArray() && toolCallsNode.size() > 0) {
                List<ToolCallRequest> toolCalls = new ArrayList<>();
                for (JsonNode tc : toolCallsNode) {
                    String id = tc.has("id") ? tc.get("id").asText() : "";
                    JsonNode func = tc.get("function");
                    String name = func != null && func.has("name") ? func.get("name").asText() : "";
                    String args = func != null && func.has("arguments") ? func.get("arguments").asText() : "{}";
                    toolCalls.add(new ToolCallRequest(id, name, args));
                }
                log.debug("DeepSeek returned {} tool call(s)", toolCalls.size());
                return new ChatToolResponse(null, toolCalls);
            }

            // 普通文本响应
            String content = "";
            JsonNode contentNode = message.get("content");
            if (contentNode != null && !contentNode.isNull()) {
                content = contentNode.asText();
            }
            return new ChatToolResponse(content, List.of());
        } catch (Exception e) {
            if (e instanceof BusinessException) {
                throw (BusinessException) e;
            }
            throw new BusinessException(ErrorCode.DEEPSEEK_API_ERROR,
                    "Failed to parse DeepSeek API tool response: " + e.getMessage(), e);
        }
    }

    private String extractStreamContent(String jsonLine) {
        try {
            JsonNode root = objectMapper.readTree(jsonLine);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null) {
                    // deepseek-v4-pro 在流式模式下 content 可能为 null，
                    // 实际文本在 reasoning_content 中（orchestrator agent 依赖此行为）
                    JsonNode content = delta.get("content");
                    if (content != null && !content.isNull()) {
                        String text = content.asText();
                        if (!text.isEmpty()) {
                            log.debug("DeepSeek stream chunk: '{}'", text);
                            return text;
                        }
                    }
                    // 回退到 reasoning_content（orchestrator agent 需要此行为）
                    JsonNode reasoning = delta.get("reasoning_content");
                    if (reasoning != null && !reasoning.isNull()) {
                        String text = reasoning.asText();
                        if (!text.isEmpty()) {
                            log.debug("DeepSeek stream reasoning: '{}'", text);
                            return text;
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse stream line: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从流式行中提取完整的 StreamChunk（含 reasoning 和 content 分离）.
     */
    private StreamChunk extractStreamChunk(String jsonLine) {
        try {
            JsonNode root = objectMapper.readTree(jsonLine);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null) {
                    String content = null, reasoning = null;
                    JsonNode contentNode = delta.get("content");
                    if (contentNode != null && !contentNode.isNull() && !contentNode.asText().isEmpty()) {
                        content = contentNode.asText();
                    }
                    JsonNode reasoningNode = delta.get("reasoning_content");
                    if (reasoningNode != null && !reasoningNode.isNull() && !reasoningNode.asText().isEmpty()) {
                        reasoning = reasoningNode.asText();
                    }
                    if (content != null || reasoning != null) {
                        return new StreamChunk(content, reasoning);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse stream chunk: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 调用DeepSeek Chat API（SSE流式模式，分离 reasoning 和 content）.
     */
    public Flux<StreamChunk> chatStreamWithReasoning(List<ChatMessage> messages) {
        return chatStreamWithReasoning(messages, this.maxTokens, this.temperature, this.timeoutSeconds);
    }

    public Flux<StreamChunk> chatStreamWithReasoning(List<ChatMessage> messages, int timeoutSecs) {
        return chatStreamWithReasoning(messages, this.maxTokens, this.temperature, timeoutSecs);
    }

    public Flux<StreamChunk> chatStreamWithReasoning(List<ChatMessage> messages, int maxTokens, double temperature) {
        return chatStreamWithReasoning(messages, maxTokens, temperature, this.timeoutSeconds);
    }

    public Flux<StreamChunk> chatStreamWithReasoning(List<ChatMessage> messages, int maxTokens, double temperature, int timeoutSecs) {
        log.debug("Calling DeepSeek Chat API (stream with reasoning) with {} messages", messages.size());

        Map<String, Object> requestBody = buildRequestBody(messages, maxTokens, temperature, true);

        return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchangeToFlux(response -> {
                    if (response.statusCode().is4xxClientError() || response.statusCode().is5xxServerError()) {
                        return response.bodyToMono(String.class)
                                .flatMapMany(body -> Flux.error(new BusinessException(
                                        ErrorCode.DEEPSEEK_API_ERROR,
                                        "DeepSeek API stream error: " + response.statusCode() + " - " + body)));
                    }
                    return response.bodyToFlux(String.class);
                })
                .flatMap(chunk -> Flux.fromArray(chunk.split("\n")))
                .doOnNext(line -> log.debug("DeepSeek stream raw line: {}", line))
                .map(line -> {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) return trimmed;
                    if (trimmed.startsWith(":")) return "";
                    if (trimmed.startsWith("data: ")) {
                        trimmed = trimmed.substring(6).trim();
                    } else if (trimmed.startsWith("data:")) {
                        trimmed = trimmed.substring(5).trim();
                    }
                    return trimmed;
                })
                .filter(line -> !line.isEmpty() && !"[DONE]".equals(line))
                .mapNotNull(this::extractStreamChunk)
                .timeout(Duration.ofSeconds(timeoutSecs))
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    return new BusinessException(ErrorCode.DEEPSEEK_API_ERROR,
                            "Failed to stream from DeepSeek API: " + e.getMessage(), e);
                });
    }
}
