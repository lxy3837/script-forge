package com.erchuang.scriptforge.vectordb;

/**
 * 向量检索结果封装——包含相似度得分和关联的向量文档.
 *
 * @author ScriptForge Team
 */
public class SearchResult {

    /** 关联的记录ID */
    private final long id;

    /** 相似度得分 */
    private final double score;

    /** 原始文本 */
    private final String text;

    public SearchResult(long id, double score, String text) {
        this.id = id;
        this.score = score;
        this.text = text;
    }

    public long getId() {
        return id;
    }

    public double getScore() {
        return score;
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return String.format("SearchResult{id=%d, score=%.4f, text='%s'}", id, score,
                text != null && text.length() > 50 ? text.substring(0, 50) + "..." : text);
    }
}
