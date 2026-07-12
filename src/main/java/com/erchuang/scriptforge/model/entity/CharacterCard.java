package com.erchuang.scriptforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 人设卡片实体——存储角色的完整人设信息及Embedding向量.
 *
 * @author ScriptForge Team
 */
@Entity
@Table(name = "character_card", indexes = {
        @Index(name = "idx_character_game_name", columnList = "game_name, name")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CharacterCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 角色名称 */
    @Column(name = "name", length = 100, nullable = false)
    private String name;

    /** 所属游戏名称 */
    @Column(name = "game_name", length = 100, nullable = false)
    private String gameName;

    /** 所属项目ID（可选，用于项目删除时级联清理） */
    @Column(name = "project_id")
    private Long projectId;

    /** 性格特征描述 */
    @Lob
    @Column(name = "personality")
    private String personality;

    /** 外貌描述 */
    @Lob
    @Column(name = "appearance")
    private String appearance;

    /** 背景故事 */
    @Lob
    @Column(name = "background")
    private String background;

    /** 人际关系（JSON格式） */
    @Lob
    @Column(name = "relationships")
    private String relationships;

    /** 经典台词（JSON数组） */
    @Lob
    @Column(name = "classic_quotes")
    private String classicQuotes;

    /**
     * Embedding向量，以BLOB形式存储float[]的序列化字节。
     * 由 LuceneVectorStore 进行实际索引和检索，此字段为持久化备份。
     */
    @Lob
    @Column(name = "embedding", columnDefinition = "BLOB")
    @Basic(fetch = FetchType.LAZY)
    private byte[] embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 将float[]转换为byte[]用于存储.
     *
     * @param floats float数组
     * @return 字节数组
     */
    public static byte[] floatsToBytes(float[] floats) {
        if (floats == null) {
            return null;
        }
        byte[] bytes = new byte[floats.length * 4];
        java.nio.ByteBuffer.wrap(bytes).asFloatBuffer().put(floats);
        return bytes;
    }

    /**
     * 将byte[]转换为float[].
     *
     * @param bytes 字节数组
     * @return float数组
     */
    public static float[] bytesToFloats(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        float[] floats = new float[bytes.length / 4];
        java.nio.ByteBuffer.wrap(bytes).asFloatBuffer().get(floats);
        return floats;
    }

    /**
     * 获取embedding的float[]形式.
     */
    public float[] getEmbeddingFloats() {
        return bytesToFloats(this.embedding);
    }

    /**
     * 设置embedding的float[]形式.
     */
    public void setEmbeddingFloats(float[] floats) {
        this.embedding = floatsToBytes(floats);
    }
}
