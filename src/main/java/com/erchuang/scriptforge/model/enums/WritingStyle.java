package com.erchuang.scriptforge.model.enums;

/**
 * 写作风格（文风）枚举.
 *
 * @author ScriptForge Team
 */
public enum WritingStyle {

    /** 轻小说风格 */
    LIGHT_NOVEL("轻小说"),

    /** 戏剧风格 */
    DRAMA("戏剧"),

    /** 小说体 */
    NOVEL("小说体"),

    /** 脚本/台词体 */
    SCRIPT("脚本/台词体");

    private final String displayName;

    WritingStyle(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
