package com.erchuang.scriptforge.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt模板管理器——从resources/prompts/目录加载模板文件并填充变量.
 * <p>
 * 模板使用 {{variableName}} 语法表示占位符。
 * 模板文件格式：
 * - 第一行为SYSTEM prompt（以[SYSTEM]开头，可选）
 * - 之后为USER prompt内容
 * </p>
 *
 * @author ScriptForge Team
 */
@Component
public class PromptTemplate {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplate.class);

    /** 模板占位符正则：{{variableName}} */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    /** 模板缓存 */
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    /**
     * 加载指定名称的模板文件.
     *
     * @param templateName 模板名称（不含路径前缀和扩展名，如 "requirement-system"）
     * @return 模板内容
     */
    public String load(String templateName) {
        return templateCache.computeIfAbsent(templateName, name -> {
            String filePath = "prompts/" + name + ".txt";
            try {
                ClassPathResource resource = new ClassPathResource(filePath);
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                log.debug("Loaded prompt template: {}", filePath);
                return content;
            } catch (IOException e) {
                log.error("Failed to load prompt template: {}", filePath, e);
                return "";
            }
        });
    }

    /**
     * 加载模板并填充变量.
     *
     * @param templateName 模板名称
     * @param variables    变量映射
     * @return 填充后的模板内容
     */
    public String render(String templateName, Map<String, String> variables) {
        String template = load(templateName);
        return renderString(template, variables);
    }

    /**
     * 对模板字符串进行变量替换.
     *
     * @param template  模板字符串
     * @param variables 变量映射
     * @return 填充后的字符串
     */
    public String renderString(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (variables == null || variables.isEmpty()) {
            return template;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 从模板中提取SYSTEM prompt（如果模板以[SYSTEM]开头）.
     *
     * @param templateContent 模板完整内容
     * @return SYSTEM prompt内容，无则返回null
     */
    public String extractSystemPrompt(String templateContent) {
        if (templateContent == null || templateContent.isEmpty()) {
            return null;
        }
        String[] parts = templateContent.split("\\[USER]", 2);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("[SYSTEM]")) {
                return trimmed.substring(8).trim();
            }
        }
        return null;
    }

    /**
     * 从模板中提取USER prompt（如果模板以[SYSTEM]开头则提取[USER]后的部分）.
     *
     * @param templateContent 模板完整内容
     * @return USER prompt内容
     */
    public String extractUserPrompt(String templateContent) {
        if (templateContent == null || templateContent.isEmpty()) {
            return "";
        }
        String[] parts = templateContent.split("\\[USER]", 2);
        if (parts.length > 1) {
            return parts[1].trim();
        }
        return templateContent.trim();
    }
}
