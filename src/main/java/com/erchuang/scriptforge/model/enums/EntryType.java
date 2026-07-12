package com.erchuang.scriptforge.model.enums;

/**
 * 知识库条目类型枚举.
 *
 * @author ScriptForge Team
 */
public enum EntryType {

    /** 角色 */
    CHARACTER("角色"),

    /** 世界观/传说 */
    LORE("世界观/传说"),

    /** 事件 */
    EVENT("事件"),

    /** 游戏机制 */
    MECHANICS("游戏机制");

    private final String displayName;

    EntryType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
