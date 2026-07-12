package com.erchuang.scriptforge.model.enums;

/**
 * 问题严重等级枚举，用于审核报告中的问题分级.
 *
 * @author ScriptForge Team
 */
public enum SeverityLevel {

    /** 严重 - 必须修改 */
    CRITICAL("严重", 3),

    /** 中等 - 建议修改 */
    MODERATE("中等", 2),

    /** 轻微 - 可选修改 */
    MINOR("轻微", 1);

    private final String displayName;
    private final int level;

    SeverityLevel(String displayName, int level) {
        this.displayName = displayName;
        this.level = level;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getLevel() {
        return level;
    }
}
