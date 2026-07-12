package com.erchuang.scriptforge.model.enums;

/**
 * 篇幅级别枚举.
 *
 * @author ScriptForge Team
 */
public enum ScopeLevel {

    /** 短篇：3-5章 */
    SHORT("短篇", 3, 5),

    /** 中篇：5-10章 */
    MEDIUM("中篇", 5, 10),

    /** 长篇：10-20章 */
    LONG("长篇", 10, 20);

    private final String displayName;
    private final int minChapters;
    private final int maxChapters;

    ScopeLevel(String displayName, int minChapters, int maxChapters) {
        this.displayName = displayName;
        this.minChapters = minChapters;
        this.maxChapters = maxChapters;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getMinChapters() {
        return minChapters;
    }

    public int getMaxChapters() {
        return maxChapters;
    }
}
