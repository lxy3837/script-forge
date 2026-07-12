package com.erchuang.scriptforge.config;

import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEventHttpMessageReader;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * 应用全局配置类——提供通用Bean和WebClient配置.
 *
 * @author ScriptForge Team
 */
@Configuration
public class AppConfig {

    /**
     * 创建带连接池和超时限制的 HttpClient.
     */
    @Bean
    public ConnectionProvider connectionProvider() {
        return ConnectionProvider.builder("script-forge-pool")
                .maxConnections(20)            // 最大连接总数
                .pendingAcquireMaxCount(50)    // 最大等待获取连接数
                .pendingAcquireTimeout(Duration.ofSeconds(30))  // 等待获取连接超时
                .maxIdleTime(Duration.ofSeconds(60))            // 空闲连接最大存活时间
                .build();
    }

    /**
     * 创建WebClient Bean（复用连接池，限制并发连接数，配置超时）.
     */
    @Bean
    public WebClient webClient(DeepSeekConfig deepSeekConfig, ConnectionProvider connectionProvider) {
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .responseTimeout(Duration.ofSeconds(
                        deepSeekConfig.getChat().getTimeoutSeconds() + 10))  // 响应超时（比 API 超时多 10s）
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(600))
                            .addHandlerLast(new WriteTimeoutHandler(60)));

        return WebClient.builder()
                .baseUrl(deepSeekConfig.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + deepSeekConfig.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> {
                            configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024);
                        })
                        .build())
                .build();
    }
}
