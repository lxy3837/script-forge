package com.erchuang.scriptforge.web.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 系统配置管理控制器——提供 application.yml 的可视化编辑接口.
 *
 * @author ScriptForge Team
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final Resource yamlResource;
    private final File yamlFile;

    public ConfigController(@Value("classpath:application.yml") Resource yamlResource) {
        this.yamlResource = yamlResource;
        this.yamlFile = new File("src/main/resources/application.yml");
    }

    /**
     * 获取当前全部配置.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> config;
            try (InputStream is = yamlResource.getInputStream()) {
                config = yaml.load(is);
            }
            return ResponseEntity.ok(config != null ? config : new LinkedHashMap<>());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 保存配置（深度合并到现有配置，保留未在表单中出现的字段）.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> saveConfig(@RequestBody Map<String, Object> newConfig) {
        try {
            // 先读取现有配置
            Yaml yaml = new Yaml();
            Map<String, Object> existing;
            try (InputStream is = yamlResource.getInputStream()) {
                existing = yaml.load(is);
            }
            if (existing == null) {
                existing = new LinkedHashMap<>();
            }

            // 深度合并
            deepMerge(existing, newConfig);

            // 写回文件
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(4);
            options.setIndicatorIndent(2);
            Yaml dumper = new Yaml(options);

            String yamlStr = dumper.dump(existing);

            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(yamlFile), StandardCharsets.UTF_8))) {
                writer.write(yamlStr);
            }

            Map<String, String> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "配置已保存，重启后生效");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("status", "error");
            result.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceVal = entry.getValue();
            Object targetVal = target.get(key);

            if (sourceVal instanceof Map && targetVal instanceof Map) {
                // 递归合并嵌套 Map
                Map<String, Object> targetMap = (Map<String, Object>) targetVal;
                Map<String, Object> sourceMap = (Map<String, Object>) sourceVal;
                // 只更新 source 中存在的键，保留 target 中独有的键
                for (Map.Entry<String, Object> subEntry : sourceMap.entrySet()) {
                    String subKey = subEntry.getKey();
                    Object subSourceVal = subEntry.getValue();
                    Object subTargetVal = targetMap.get(subKey);

                    if (subSourceVal instanceof Map && subTargetVal instanceof Map) {
                        deepMerge((Map<String, Object>) subTargetVal, (Map<String, Object>) subSourceVal);
                    } else {
                        targetMap.put(subKey, subSourceVal);
                    }
                }
            } else {
                target.put(key, sourceVal);
            }
        }
    }

}
