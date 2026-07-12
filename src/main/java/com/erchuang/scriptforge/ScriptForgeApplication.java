package com.erchuang.scriptforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ScriptForge 启动入口类.
 * <p>
 * 基于Java+DeepSeek V4 Pro的多Agent二游二创剧本生成系统。
 * 纯本地运行的Spring Boot单体应用，集成H2数据库、Lucene向量检索、
 * DeepSeek LLM调用及多格式文档导出引擎。
 * </p>
 *
 * @author ScriptForge Team
 * @version 1.0.0
 */
@SpringBootApplication
@EnableScheduling
public class ScriptForgeApplication {

    /**
     * 应用程序主入口.
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ScriptForgeApplication.class, args);
    }
}
