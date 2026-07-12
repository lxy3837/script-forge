package com.erchuang.scriptforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek API 配置类——从application.yml读取DeepSeek相关配置.
 *
 * @author ScriptForge Team
 */
@Configuration
@ConfigurationProperties(prefix = "deepseek")
@Data
public class DeepSeekConfig {

    /** DeepSeek API Key */
    private String apiKey;

    /** API基础URL */
    private String baseUrl;

    /** Chat配置 */
    private ChatConfig chat = new ChatConfig();

    /** Embedding配置 */
    private EmbeddingConfig embedding = new EmbeddingConfig();

    /** 最大重试次数 */
    private int maxRetries = 3;

    @Data
    public static class ChatConfig {
        private String model = "deepseek-chat";
        private int maxTokens = 4096;
        private double temperature = 0.7;
        private int timeoutSeconds = 60;
    }

    @Data
    public static class EmbeddingConfig {
        private String model = "deepseek-embed";
        private int timeoutSeconds = 30;
    }
}
