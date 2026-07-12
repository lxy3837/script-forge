package com.erchuang.scriptforge.agent.review;

import com.erchuang.scriptforge.llm.EmbeddingService;
import com.erchuang.scriptforge.model.entity.CharacterCard;
import com.erchuang.scriptforge.repository.CharacterCardRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于多参考点向量相似度 + 签名词匹配 + 场景自适应的OOC检测器.
 * <p>
 * 不调用LLM，纯本地计算。三重机制：
 * 1. 多参考点比对：性格/背景/经典台词各生成embedding，取最高相似度
 * 2. 签名词加权：台词中命中角色经典口头禅时大幅加分
 * 3. 场景自适应：战斗场景阈值相比日常场景更宽松
 * </p>
 *
 * @author ScriptForge Team
 */
public class OOCVectorDetector {

    private static final Logger log = LoggerFactory.getLogger(OOCVectorDetector.class);

    /** 日常场景基础阈值 */
    private static final double DAILY_OOC_THRESHOLD = 0.52;
    /** 战斗场景基础阈值（更宽松，战斗台词偏离正常） */
    private static final double COMBAT_OOC_THRESHOLD = 0.38;
    /** 严重OOC阈值（日常） */
    private static final double SEVERE_DAILY_THRESHOLD = 0.32;
    /** 严重OOC阈值（战斗） */
    private static final double SEVERE_COMBAT_THRESHOLD = 0.22;
    /** 签名词命中加分（每个命中词加0.15） */
    private static final double SIGNATURE_BONUS = 0.15;
    /** 签名词最大加分上限 */
    private static final double MAX_SIGNATURE_BONUS = 0.40;
    /** 默认采样台词数 */
    private static final int DEFAULT_SAMPLE_SIZE = 5;

    /** 战斗场景关键词 */
    private static final Set<String> COMBAT_KEYWORDS = Set.of(
            "战斗", "攻击", "防御", "斩", "杀", "死", "毁灭", "破坏",
            "武器", "护盾", "魔法", "元素", "爆发", "绝招", "必杀"
    );

    private final EmbeddingService embeddingService;
    private final CharacterCardRepository characterCardRepository;
    private final ObjectMapper objectMapper;

    public OOCVectorDetector(EmbeddingService embeddingService,
                              CharacterCardRepository characterCardRepository,
                              ObjectMapper objectMapper) {
        this.embeddingService = embeddingService;
        this.characterCardRepository = characterCardRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 检测剧本中的OOC问题（多参考点 + 签名词 + 场景自适应）.
     */
    public String detect(String scriptContent, String characterCards) {
        try {
            Map<String, List<DialogueLine>> characterDialogues = extractDialogues(scriptContent);
            if (characterDialogues.isEmpty()) {
                return buildEmptyResult();
            }

            // 整体场景分类：判断是战斗向还是日常向剧本
            boolean isCombatHeavy = classifyScriptScene(scriptContent);

            // 构建角色多参考点embedding缓存
            Map<String, CharacterRefEmbeddings> characterRefs = buildMultiRefCache(characterDialogues.keySet());

            List<OOCIssue> issues = new ArrayList<>();
            int totalChecked = 0;
            int oocCount = 0;

            for (Map.Entry<String, List<DialogueLine>> entry : characterDialogues.entrySet()) {
                String characterName = entry.getKey();
                List<DialogueLine> dialogues = entry.getValue();
                CharacterRefEmbeddings refs = characterRefs.get(characterName);
                if (refs == null || refs.isEmpty()) continue;

                // 智能采样：均匀分布取DEFAULT_SAMPLE_SIZE句
                int total = dialogues.size();
                int sampleSize = Math.min(total, DEFAULT_SAMPLE_SIZE);
                int step = total / sampleSize;

                for (int si = 0; si < sampleSize; si++) {
                    int idx = Math.min(si * step, total - 1);
                    DialogueLine dl = dialogues.get(idx);
                    totalChecked++;

                    // 判断该台词所属场景类型
                    boolean isCombatLine = isCombatScene(dl);

                    // 多参考点比对
                    double bestSim = computeBestSimilarity(refs, dl.text);

                    // 签名词加分
                    double signatureBonus = computeSignatureBonus(refs, dl.text);
                    double adjustedSim = Math.min(bestSim + signatureBonus, 1.0);

                    // 场景自适应阈值
                    double threshold = isCombatLine ? COMBAT_OOC_THRESHOLD : DAILY_OOC_THRESHOLD;
                    double severeThreshold = isCombatLine ? SEVERE_COMBAT_THRESHOLD : SEVERE_DAILY_THRESHOLD;

                    if (adjustedSim < threshold) {
                        oocCount++;
                        String severity = adjustedSim < severeThreshold ? "CRITICAL" : "MODERATE";
                        String sceneType = isCombatLine ? "战斗场景" : "日常场景";
                        String reason = String.format(
                                "多参考点最高相似度 %.2f（性格/背景/经典台词），签名词加分 +%.2f，最终得分 %.2f，低于%s阈值 %.2f",
                                bestSim, signatureBonus, adjustedSim, sceneType, threshold);

                        issues.add(new OOCIssue(
                                characterName,
                                reason,
                                severity,
                                String.format("章节%d 场景%d", dl.chapterNum, dl.sceneIndex),
                                buildSuggestion(characterName, bestSim, signatureBonus),
                                adjustedSim
                        ));
                    }
                }
            }

            log.info("OOC check done: {} lines checked, {} issues found", totalChecked, oocCount);
            return buildResult(issues, totalChecked, oocCount, isCombatHeavy);
        } catch (Exception e) {
            log.warn("OOC vector detection failed: {}", e.getMessage());
            return buildErrorResult(e.getMessage());
        }
    }

    // ========== 多参考点比对 ==========

    /**
     * 用性格、背景、经典台词三条参考线分别比对，取最高分.
     */
    private double computeBestSimilarity(CharacterRefEmbeddings refs, String dialogueText) {
        float[] dialogueEmb = embeddingService.embed(dialogueText);
        double best = 0.0;

        if (refs.personalityEmb != null) {
            best = Math.max(best, EmbeddingService.cosineSimilarity(refs.personalityEmb, dialogueEmb));
        }
        if (refs.backgroundEmb != null) {
            best = Math.max(best, EmbeddingService.cosineSimilarity(refs.backgroundEmb, dialogueEmb));
        }
        if (refs.quotesEmb != null) {
            best = Math.max(best, EmbeddingService.cosineSimilarity(refs.quotesEmb, dialogueEmb));
        }

        return best;
    }

    /**
     * 计算签名词命中加分.
     * 从角色经典台词中提取2字以上的关键词，台词中每命中一个关键词加 SIGNATURE_BONUS 分.
     */
    private double computeSignatureBonus(CharacterRefEmbeddings refs, String dialogueText) {
        if (refs.signaturePhrases == null || refs.signaturePhrases.isEmpty()) return 0.0;
        if (dialogueText == null || dialogueText.length() < 2) return 0.0;

        String lower = dialogueText.toLowerCase();
        int hitCount = 0;
        for (String phrase : refs.signaturePhrases) {
            if (phrase.length() >= 2 && lower.contains(phrase.toLowerCase())) {
                hitCount++;
            }
        }

        return Math.min(hitCount * SIGNATURE_BONUS, MAX_SIGNATURE_BONUS);
    }

    /**
     * 判断台词所在场景是否为战斗场景.
     */
    private boolean isCombatScene(DialogueLine dl) {
        if (dl.text == null) return false;
        String lower = dl.text.toLowerCase();
        for (String keyword : COMBAT_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    /**
     * 整体判断剧本类型（含战斗关键词比例超过5%则为战斗向）.
     */
    private boolean classifyScriptScene(String scriptContent) {
        if (scriptContent == null || scriptContent.isBlank()) return false;
        String lower = scriptContent.toLowerCase();
        int combatCount = 0;
        int totalLines = 0;
        for (String line : lower.split("\n")) {
            totalLines++;
            for (String kw : COMBAT_KEYWORDS) {
                if (line.contains(kw)) { combatCount++; break; }
            }
        }
        return totalLines > 0 && (double) combatCount / totalLines > 0.05;
    }

    // ========== 多参考点缓存 ==========

    /**
     * 角色多参考点embedding缓存结构.
     */
    private static class CharacterRefEmbeddings {
        float[] personalityEmb;
        float[] backgroundEmb;
        float[] quotesEmb;
        List<String> signaturePhrases; // 从classicQuotes中提取的签名词

        boolean isEmpty() {
            return personalityEmb == null && backgroundEmb == null && quotesEmb == null;
        }
    }

    private Map<String, CharacterRefEmbeddings> buildMultiRefCache(Set<String> characterNames) {
        Map<String, CharacterRefEmbeddings> cache = new HashMap<>();
        for (String name : characterNames) {
            List<CharacterCard> cards = characterCardRepository.findByName(name);
            if (cards.isEmpty()) continue;

            CharacterCard card = cards.get(0);
            CharacterRefEmbeddings refs = new CharacterRefEmbeddings();

            // 性格embedding
            if (card.getPersonality() != null && !card.getPersonality().isBlank()) {
                try {
                    refs.personalityEmb = embeddingService.embed(card.getName() + "的性格：" + card.getPersonality());
                } catch (Exception e) {
                    log.debug("Failed to embed personality for '{}': {}", name, e.getMessage());
                }
            }

            // 背景embedding
            if (card.getBackground() != null && !card.getBackground().isBlank()) {
                try {
                    String bgText = card.getBackground();
                    if (bgText.length() > 500) bgText = bgText.substring(0, 500);
                    refs.backgroundEmb = embeddingService.embed(card.getName() + "的背景：" + bgText);
                } catch (Exception e) {
                    log.debug("Failed to embed background for '{}': {}", name, e.getMessage());
                }
            }

            // 经典台词embedding
            if (card.getClassicQuotes() != null && !card.getClassicQuotes().isBlank()) {
                try {
                    refs.quotesEmb = embeddingService.embed(card.getName() + "的经典台词风格：" + card.getClassicQuotes());
                } catch (Exception e) {
                    log.debug("Failed to embed quotes for '{}': {}", name, e.getMessage());
                }
                // 同时提取签名词
                refs.signaturePhrases = extractSignaturePhrases(card.getClassicQuotes());
            }

            // 如果上面全都没有，fallback用名字+性格做原始embedding
            if (refs.isEmpty() && card.getPersonality() != null) {
                try {
                    refs.personalityEmb = embeddingService.embed(card.getName() + " " + card.getPersonality());
                } catch (Exception e) {
                    log.warn("Failed to fallback embed for '{}': {}", name, e.getMessage());
                }
            }

            cache.put(name, refs);
        }
        return cache;
    }

    /**
     * 从经典台词JSON数组中提取签名词.
     * 提取2-4字的连续中文字符串作为候选签名词.
     */
    private List<String> extractSignaturePhrases(String classicQuotesJson) {
        List<String> phrases = new ArrayList<>();
        if (classicQuotesJson == null || classicQuotesJson.isBlank()) return phrases;

        try {
            @SuppressWarnings("unchecked")
            List<String> quotes = objectMapper.readValue(classicQuotesJson, List.class);
            Set<String> seen = new HashSet<>();
            for (String quote : quotes) {
                // 从每句经典台词中提取2-4字的关键短语
                String cleaned = quote.replaceAll("[\\p{P}\\p{S}]", "").trim();
                for (int len = 4; len >= 2; len--) {
                    for (int i = 0; i + len <= cleaned.length(); i++) {
                        String phrase = cleaned.substring(i, i + len);
                        // 过滤纯数字/空白/单个标点
                        if (phrase.matches("[\\u4e00-\\u9fa5a-zA-Z]+") && phrase.length() >= 2) {
                            if (seen.add(phrase)) {
                                phrases.add(phrase);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse classic quotes JSON: {}", e.getMessage());
            // 非JSON格式，直接按行尝试提取
            for (String line : classicQuotesJson.split("[\\n,]")) {
                String cleaned = line.trim().replaceAll("^[\"']|[\"']$", "");
                if (cleaned.length() >= 2) {
                    phrases.add(cleaned);
                }
            }
        }
        return phrases;
    }

    // ========== 台词提取 ==========

    private Map<String, List<DialogueLine>> extractDialogues(String scriptContent) {
        Map<String, List<DialogueLine>> result = new LinkedHashMap<>();
        if (scriptContent == null || scriptContent.isBlank()) return result;

        Pattern dialoguePattern = Pattern.compile("\\[([^\\]]+)\\][：:]\\s*\"([^\"]*)\"|" +
                "\\[([^\\]]+)\\][：:]\\s*(.+?)(?:\\n|$)");
        Pattern chapterPattern = Pattern.compile("##\\s*第(\\d+)章");
        Pattern scenePattern = Pattern.compile("###\\s*场景(\\d+)");

        int currentChapter = 0;
        int currentScene = 0;

        for (String line : scriptContent.split("\n")) {
            String trimmed = line.trim();
            Matcher cm = chapterPattern.matcher(trimmed);
            if (cm.find()) { currentChapter = Integer.parseInt(cm.group(1)); continue; }
            Matcher sm = scenePattern.matcher(trimmed);
            if (sm.find()) { currentScene = Integer.parseInt(sm.group(1)); continue; }

            Matcher dm = dialoguePattern.matcher(trimmed);
            if (dm.find()) {
                String character = dm.group(1) != null ? dm.group(1) : dm.group(3);
                String text = dm.group(2) != null ? dm.group(2) : dm.group(4);
                if (character != null && text != null && !character.isBlank() && !text.isBlank()) {
                    result.computeIfAbsent(character.trim(), k -> new ArrayList<>())
                            .add(new DialogueLine(character.trim(), text.trim(), currentChapter, currentScene));
                }
            }
        }
        return result;
    }

    // ========== 建议生成 ==========

    private String buildSuggestion(String character, double bestSim, double signatureBonus) {
        if (signatureBonus > 0) {
            return String.format("\"%s\"的台词与角色口癖部分匹配，但整体语义偏离较大，建议对照人设调整语气和用词", character);
        }
        if (bestSim < 0.25) {
            return String.format("\"%s\"的这句台词严重偏离角色核心人设，建议重新设计该场景的对话", character);
        }
        return String.format("建议检查\"%s\"在当前场景的这句台词是否符合角色一贯的说话方式和行为逻辑", character);
    }

    // ========== JSON结果构建 ==========

    private String buildEmptyResult() {
        return "{\"issues\":[],\"summary\":\"未检测到角色台词，跳过OOC检查\",\"totalIssues\":0,\"totalChecked\":0," +
                "\"method\":\"multi_ref_vector\",\"sceneType\":\"unknown\"}";
    }

    private String buildResult(List<OOCIssue> issues, int totalChecked, int oocCount, boolean isCombatHeavy) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"issues\": [\n");
        for (int i = 0; i < issues.size(); i++) {
            OOCIssue issue = issues.get(i);
            sb.append("    {");
            sb.append("\"type\":\"OOC\",");
            sb.append("\"character\":\"").append(escapeJson(issue.character)).append("\",");
            sb.append("\"description\":\"").append(escapeJson(issue.description)).append("\",");
            sb.append("\"severity\":\"").append(issue.severity).append("\",");
            sb.append("\"location\":\"").append(escapeJson(issue.location)).append("\",");
            sb.append("\"suggestion\":\"").append(escapeJson(issue.suggestion)).append("\",");
            sb.append("\"similarity\":").append(String.format("%.4f", issue.similarity));
            sb.append("}");
            if (i < issues.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"summary\":\"多参考点向量+签名词+场景自适应OOC检测完成，" +
                "共检查").append(totalChecked).append("句台词，发现").append(oocCount).append("个问题\",\n");
        sb.append("  \"totalIssues\":").append(oocCount).append(",\n");
        sb.append("  \"totalChecked\":").append(totalChecked).append(",\n");
        sb.append("  \"sceneType\":\"").append(isCombatHeavy ? "combat" : "daily").append("\",\n");
        sb.append("  \"method\":\"multi_ref_vector\"\n");
        sb.append("}");
        return sb.toString();
    }

    private String buildErrorResult(String error) {
        return "{\"issues\":[],\"summary\":\"OOC向量检测异常:" + escapeJson(error) + "\",\"totalIssues\":0}";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private record DialogueLine(String character, String text, int chapterNum, int sceneIndex) {}

    private record OOCIssue(String character, String description, String severity,
                             String location, String suggestion, double similarity) {}
}
