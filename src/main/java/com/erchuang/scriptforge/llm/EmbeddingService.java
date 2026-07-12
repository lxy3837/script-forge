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

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Embedding向量化服务——调用DeepSeek Embedding API.
 * <p>
 * 将文本转换为向量表示，用于Lucene向量相似度检索。
 * 端点: /v1/embeddings
 * </p>
 *
 * @author ScriptForge Team
 */
@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${deepseek.embedding.model}")
    private String model;

    @Value("${deepseek.embedding.timeout-seconds}")
    private int timeoutSeconds;

    public EmbeddingService(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取单个文本的Embedding向量.
     *
     * @param text 输入文本
     * @return float[] 向量
     */
    public float[] embed(String text) {
        float[][] result = embedBatch(List.of(text));
        if (result.length > 0) {
            return result[0];
        }
        throw new BusinessException(ErrorCode.EMBEDDING_SERVICE_ERROR,
                "Empty embedding result for text: " + text);
    }

    /**
     * 批量获取文本的Embedding向量.
     *
     * @param texts 输入文本列表
     * @return float[][] 向量数组，与输入文本一一对应
     */
    public float[][] embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }

        log.debug("Calling DeepSeek Embedding API for {} texts", texts.size());

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "input", texts
        );

        try {
            String response = webClient.post()
                    .uri("/v1/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .flatMap(body -> reactor.core.publisher.Mono.error(
                                            new BusinessException(ErrorCode.EMBEDDING_SERVICE_ERROR,
                                                    "Embedding API error: " + clientResponse.statusCode()))))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            return parseEmbeddings(response, texts.size());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.EMBEDDING_SERVICE_ERROR,
                    "Failed to call Embedding API: " + e.getMessage(), e);
        }
    }

    /**
     * 计算两个向量的余弦相似度.
     *
     * @param vector1 向量1
     * @param vector2 向量2
     * @return 余弦相似度 [-1, 1]
     */
    public static double cosineSimilarity(float[] vector1, float[] vector2) {
        if (vector1 == null || vector2 == null || vector1.length != vector2.length) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            normA += vector1[i] * vector1[i];
            normB += vector2[i] * vector2[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private float[][] parseEmbeddings(String responseBody, int expectedCount) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                throw new BusinessException(ErrorCode.EMBEDDING_SERVICE_ERROR,
                        "Invalid embedding response format");
            }

            float[][] result = new float[data.size()][];
            for (int i = 0; i < data.size(); i++) {
                JsonNode embeddingNode = data.get(i).get("embedding");
                if (embeddingNode != null && embeddingNode.isArray()) {
                    float[] vector = new float[embeddingNode.size()];
                    for (int j = 0; j < embeddingNode.size(); j++) {
                        vector[j] = (float) embeddingNode.get(j).asDouble();
                    }
                    result[i] = vector;
                }
            }
            return result;
        } catch (Exception e) {
            if (e instanceof BusinessException) {
                throw (BusinessException) e;
            }
            throw new BusinessException(ErrorCode.EMBEDDING_SERVICE_ERROR,
                    "Failed to parse embedding response", e);
        }
    }
}
