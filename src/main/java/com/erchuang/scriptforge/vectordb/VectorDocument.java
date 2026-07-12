package com.erchuang.scriptforge.vectordb;

/**
 * 向量文档封装——存储向量与其关联的元数据ID.
 *
 * @author ScriptForge Team
 */
public class VectorDocument {

    /** 关联的数据库记录ID */
    private final long id;

    /** 原始文本 */
    private final String text;

    /** 向量数据 */
    private final float[] vector;

    public VectorDocument(long id, String text, float[] vector) {
        this.id = id;
        this.text = text;
        this.vector = vector;
    }

    public long getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public float[] getVector() {
        return vector;
    }

    public int getDimension() {
        return vector != null ? vector.length : 0;
    }
}
