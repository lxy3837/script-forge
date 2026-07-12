package com.erchuang.scriptforge.model.enums;

/**
 * 项目状态枚举.
 *
 * @author ScriptForge Team
 */
public enum ProjectStatus {

    /** 草稿 */
    DRAFT("草稿"),

    /** 进行中 */
    IN_PROGRESS("进行中"),

    /** 已暂停 */
    PAUSED("已暂停"),

    /** 已完成 */
    COMPLETED("已完成"),

    /** 已归档 */
    ARCHIVED("已归档");

    private final String displayName;

    ProjectStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
