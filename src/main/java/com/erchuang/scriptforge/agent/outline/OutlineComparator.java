package com.erchuang.scriptforge.agent.outline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 大纲雷同度检测器——检测多版大纲之间的相似度，触发差异化重试.
 *
 * @author ScriptForge Team
 */
public class OutlineComparator {

    private static final Logger log = LoggerFactory.getLogger(OutlineComparator.class);

    /** 雷同度阈值：高于此值认定为雷同 */
    private static final double SIMILARITY_THRESHOLD = 0.7;

    /**
     * 检查多版大纲是否足够差异化.
     *
     * @param outlines 大纲文本列表
     * @return true 如果任意两版过于相似
     */
    public boolean hasDuplicate(List<String> outlines) {
        if (outlines == null || outlines.size() < 2) {
            return false;
        }

        for (int i = 0; i < outlines.size(); i++) {
            for (int j = i + 1; j < outlines.size(); j++) {
                double similarity = calculateSimilarity(outlines.get(i), outlines.get(j));
                if (similarity > SIMILARITY_THRESHOLD) {
                    log.warn("Outlines {} and {} are too similar (similarity={:.2f})",
                            i + 1, j + 1, similarity);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 计算两段文本的相似度（简化版：基于共同词频）.
     *
     * @param text1 文本1
     * @param text2 文本2
     * @return 相似度 [0, 1]
     */
    public double calculateSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null || text1.isEmpty() || text2.isEmpty()) {
            return 0.0;
        }

        // 简化实现：基于字符重叠度
        java.util.Set<Character> set1 = new java.util.HashSet<>();
        java.util.Set<Character> set2 = new java.util.HashSet<>();

        for (char c : text1.toCharArray()) {
            set1.add(c);
        }
        for (char c : text2.toCharArray()) {
            set2.add(c);
        }

        java.util.Set<Character> intersection = new java.util.HashSet<>(set1);
        intersection.retainAll(set2);

        java.util.Set<Character> union = new java.util.HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }
}
