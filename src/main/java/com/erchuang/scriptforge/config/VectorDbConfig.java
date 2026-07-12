package com.erchuang.scriptforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 向量数据库配置类——Lucene索引相关配置.
 *
 * @author ScriptForge Team
 */
@Configuration
@ConfigurationProperties(prefix = "vector-db")
@Data
public class VectorDbConfig {

    /** Lucene索引存储目录 */
    private String indexDir = "./data/lucene-index";

    /** 向量维度 */
    private int dimension = 1024;

    /** HNSW配置 */
    private HnswConfig hnsw = new HnswConfig();

    @Data
    public static class HnswConfig {
        /** HNSW的M参数（每个节点的最大连接数） */
        private int m = 16;

        /** HNSW的efConstruction参数（构建时的搜索深度） */
        private int efConstruction = 200;
    }
}
