package com.erchuang.scriptforge.model.enums;

/**
 * 剧本状态枚举.
 *
 * @author ScriptForge Team
 */
public enum ScriptStatus {

    /** 草稿 */
    DRAFT("草稿"),

    /** 审核中 */
    REVIEWING("审核中"),

    /** 已修订 */
    REVISED("已修订"),

    /** 终稿 */
    FINAL("终稿");

    private final String displayName;

    ScriptStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
