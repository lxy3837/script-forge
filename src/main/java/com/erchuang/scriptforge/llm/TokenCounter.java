package com.erchuang.scriptforge.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Token用量估算器——基于字符数粗略估算Token使用量，支持上下文类型感知的智能截断.
 * <p>
 * 由于DeepSeek使用BPE分词器，精确Token计数需要调用API。
 * 本类采用字符数近似估算策略：
 * - 中文字符约 1 token/字
 * - 英文单词约 1.3 token/词
 * - 综合平均值约 1.5 字符/token
 * </p>
 *
 * @author ScriptForge Team
 */
@Component
public class TokenCounter {

    private static final Logger log = LoggerFactory.getLogger(TokenCounter.class);

    /** 中文Token转换因子：每中文汉字约1个token */
    private static final double CHINESE_CHAR_TO_TOKEN = 1.0;

    /** 英文Token转换因子：每单词约1.3个token */
    private static final double ENGLISH_WORD_TO_TOKEN = 1.3;

    /** 混合文本保守转换因子：每1.5字符约1个token */
    private static final double CHAR_TO_TOKEN_RATIO = 1.5;

    /** DeepSeek Chat API单次请求上限 */
    private static final int CONTEXT_WINDOW = 128000;

    /** 截断后回溯搜索窗口比例（20%） */
    private static final double BACKTRACK_RATIO = 0.8;

    /** 叙事文本的锚点字符：句号、感叹号、换行 */
    private static final char[] NARRATIVE_ANCHORS = {'\n', '。', '.', '！', '!', '？', '?', '…'};

    /** JSON文本的锚点字符：逗号、闭合括号 */
    private static final char[] JSON_ANCHORS = {',', '}', ']'};

    /** 截断标记 */
    private static final String TRUNCATION_MARKER = "\n\n[内容过长已截断...]";

    /**
     * 中文动词/形容词常见结尾字符——用于判断截断后句子是否不完整。
     * 包含：动词尾（了、着、过、地、得）、中顿（，、；）、连词（而、并、且、和、与）
     */
    private static final String INCOMPLETE_ENDING_CHARS = "，,；;而并且和与的了着过得地";

    /**
     * 截断兜底微指令：当文本以不完整句子结尾时追加，引导LLM自行补全.
     */
    private static final String INCOMPLETE_HINT =
            "\n\n（注：以上文本由于长度限制被截断，请基于以上不完整的上下文继续创作，优先补全最后一个角色的动作或对话。）";

    @Value("${deepseek.chat.max-tokens}")
    private int maxTokensPerRequest;

    // ==================== Token估算 ====================

    /**
     * 估算文本的Token数量.
     *
     * @param text 输入文本
     * @return 估算Token数
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHAR_TO_TOKEN_RATIO);
    }

    /**
     * 精确估算：区分中英文.
     *
     * @param text 输入文本
     * @return 估算Token数
     */
    public int estimateTokensPrecise(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        double tokens = 0.0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                tokens += CHINESE_CHAR_TO_TOKEN;
            } else if (Character.isWhitespace(c)) {
                tokens += 0.25;
            } else {
                tokens += 0.3;
            }
        }

        // 加上英文单词因子补偿
        String[] words = text.split("\\s+");
        tokens += words.length * 0.3;

        return (int) Math.ceil(tokens);
    }

    // ==================== 上限检查 ====================

    /**
     * 检查文本是否超过单次请求Token上限.
     */
    public boolean mayExceedLimit(String text) {
        return estimateTokens(text) > maxTokensPerRequest;
    }

    /**
     * 检查文本是否超过上下文窗口.
     */
    public boolean mayExceedContextWindow(String text) {
        return estimateTokens(text) > CONTEXT_WINDOW;
    }

    // ==================== 截断主入口 ====================

    /**
     * 类型感知的智能截断——根据上下文类型选择最优截断策略.
     *
     * @param text        原始文本
     * @param maxTokens   最大Token数
     * @param contextType 上下文类型（NARRATIVE / JSON）
     * @return 截断后的文本
     */
    public String truncateToTokenLimit(String text, int maxTokens, ContextType contextType) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        int estimatedTokens = estimateTokens(text);
        if (estimatedTokens <= maxTokens) {
            return text;
        }

        int targetChars = (int) (maxTokens * CHAR_TO_TOKEN_RATIO);
        if (targetChars >= text.length()) {
            return text;
        }

        // 如果未显式指定类型，自动检测
        if (contextType == null) {
            contextType = detectContextType(text);
        }

        String truncated = switch (contextType) {
            case JSON -> truncateJson(text, targetChars);
            case NARRATIVE -> truncateNarrative(text, targetChars);
        };

        log.debug("Truncated text from {} chars to {} chars (~{} tokens), type={}",
                text.length(), truncated.length(), estimateTokens(truncated), contextType);

        return truncated;
    }

    /**
     * 无类型感知的截断（向后兼容，默认使用 NARRATIVE 策略）.
     */
    public String truncateToTokenLimit(String text, int maxTokens) {
        return truncateToTokenLimit(text, maxTokens, null);
    }

    // ==================== 自动类型检测 ====================

    /**
     * 根据文本特征自动检测上下文类型.
     */
    private ContextType detectContextType(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return ContextType.JSON;
        }
        return ContextType.NARRATIVE;
    }

    // ==================== 叙事文本截断 ====================

    /**
     * 叙事文本截断：在锚点字符（句号/感叹号/问号/省略号/换行）处截断.
     */
    private String truncateNarrative(String text, int targetChars) {
        int cutPoint = findCutPoint(text, targetChars, NARRATIVE_ANCHORS);
        String truncated = text.substring(0, cutPoint);
        truncated = appendIncompleteHintIfNeeded(truncated);
        return truncated + TRUNCATION_MARKER;
    }

    /**
     * 在文本中回溯寻找最近的锚点字符作为截断位置.
     *
     * @param text       原始文本
     * @param targetChars 目标字符位置
     * @param anchors    锚点字符数组
     * @return 截断位置（字符索引，不含）
     */
    private int findCutPoint(String text, int targetChars, char[] anchors) {
        int backtrackStart = (int) (targetChars * BACKTRACK_RATIO);
        if (backtrackStart < 0) backtrackStart = 0;

        char[] chars = text.toCharArray();
        for (int i = targetChars - 1; i >= backtrackStart; i--) {
            char c = chars[i];
            for (char anchor : anchors) {
                if (c == anchor) {
                    return i + 1; // 包含锚点字符本身
                }
            }
        }

        // 回溯窗口内找不到锚点 → 退化为 targetChars 处硬截断
        return targetChars;
    }

    // ==================== JSON 结构感知截断 ====================

    /**
     * JSON结构感知截断——保持JSON合法性.
     * <p>
     * 策略：
     * 1. 在目标位置附近寻找最近的逗号或闭合括号作为截断点
     * 2. 如果截断后括号不配对，补上闭合括号
     * 3. 用 "..." 填充被截断的值，保持JSON语法的完整性
     * </p>
     */
    private String truncateJson(String text, int targetChars) {
        String trimmed = text.trim();
        boolean startsWithBrace = trimmed.startsWith("{");
        boolean startsWithBracket = trimmed.startsWith("[");

        // 寻找截断点：优先找逗号，其次闭合括号
        int cutPoint = findJsonCutPoint(trimmed, targetChars);

        String truncated = trimmed.substring(0, cutPoint).trim();

        // 去除尾部多余逗号
        while (truncated.endsWith(",")) {
            truncated = truncated.substring(0, truncated.length() - 1).trim();
        }

        // 如果截断导致字符串或属性值被切断，补 "..." 占位
        // 检查最后一个完整结构是否闭合
        if (isValueTruncated(truncated)) {
            truncated = truncated + "\"...\"";
        }

        // 补全未闭合的 JSON 结构
        truncated = closeJsonStructure(truncated, startsWithBrace, startsWithBracket);

        return truncated + TRUNCATION_MARKER;
    }

    /**
     * 寻找 JSON 文本的最近有效截断点.
     */
    private int findJsonCutPoint(String text, int targetChars) {
        // 首先尝试在 targetChars 附近找锚点
        int cutPoint = findCutPoint(text, targetChars, JSON_ANCHORS);

        // 如果是逗号处截断，直接使用
        if (cutPoint > 0 && text.charAt(cutPoint - 1) == ',') {
            return cutPoint;
        }

        // 如果是在 '}' 或 ']' 处截断，确保这是层级的自然闭合
        if (cutPoint > 0) {
            char c = text.charAt(cutPoint - 1);
            if (c == '}' || c == ']') {
                return cutPoint;
            }
        }

        // 退化为 targetChars 处截断
        return targetChars;
    }

    /**
     * 检查截断后可能切断了属性值或字符串.
     */
    private boolean isValueTruncated(String text) {
        if (text.isEmpty()) return false;
        String trimmed = text.trim();
        // 以冒号结尾：说明切断了值
        if (trimmed.endsWith(":")) return true;
        // 以逗号结尾且前一个是字符串值的一部分
        char lastChar = trimmed.charAt(trimmed.length() - 1);
        // 如果当前截断点在不完整引号内
        long quoteCount = trimmed.chars().filter(c -> c == '"').count();
        return quoteCount % 2 != 0;
    }

    /**
     * 补全未闭合的 JSON 结构（大括号/中括号）.
     */
    private String closeJsonStructure(String text, boolean startsWithBrace, boolean startsWithBracket) {
        int braceDepth = 0;
        int bracketDepth = 0;
        boolean inString = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) continue;

            if (c == '{') braceDepth++;
            else if (c == '}') braceDepth--;
            else if (c == '[') bracketDepth++;
            else if (c == ']') bracketDepth--;
        }

        StringBuilder sb = new StringBuilder(text);
        // 先闭合中括号
        while (bracketDepth > 0) {
            sb.append(']');
            bracketDepth--;
        }
        // 再闭合大括号
        while (braceDepth > 0) {
            sb.append('}');
            braceDepth--;
        }

        return sb.toString();
    }

    // ==================== 微指令兜底 ====================

    /**
     * 当截断后的文本以不完整的句子结尾时，追加兜底微指令.
     * <p>
     * 检测规则：结尾字符属于"不完整结尾字符集"（逗号、连词、助词等），
     * 说明句子被中途切断，需要LLM自行补全.
     * </p>
     */
    private String appendIncompleteHintIfNeeded(String truncated) {
        if (truncated == null || truncated.isEmpty()) return truncated;

        char lastChar = truncated.charAt(truncated.length() - 1);

        // 如果以自然结束符结尾，无需追加
        if (lastChar == '。' || lastChar == '.' || lastChar == '！' || lastChar == '!'
                || lastChar == '？' || lastChar == '?' || lastChar == '\n' || lastChar == '…') {
            return truncated;
        }

        // 检查是否以不完整结尾字符结束
        if (INCOMPLETE_ENDING_CHARS.indexOf(lastChar) >= 0) {
            return truncated + INCOMPLETE_HINT;
        }

        return truncated;
    }

    // ==================== Getter ====================

    public int getContextWindow() {
        return CONTEXT_WINDOW;
    }

    public int getMaxTokensPerRequest() {
        return maxTokensPerRequest;
    }
}
