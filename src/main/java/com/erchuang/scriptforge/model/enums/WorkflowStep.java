package com.erchuang.scriptforge.model.enums;

/**
 * 工作流步骤枚举——定义多Agent编排的步骤顺序及依赖关系.
 *
 * @author ScriptForge Team
 */
public enum WorkflowStep {

    /** 初始状态 */
    INIT("INIT", "初始化", null),

    /** 需求调研 */
    REQUIREMENT_GATHERING("REQUIREMENT_GATHERING", "需求调研", INIT),

    /** 搜索与角色检索（并行） */
    SEARCH_AND_CHARACTER("SEARCH_AND_CHARACTER", "信息检索", REQUIREMENT_GATHERING),

    /** 大纲设计 */
    OUTLINE_DESIGN("OUTLINE_DESIGN", "大纲设计", SEARCH_AND_CHARACTER),

    /** 剧本生成 */
    SCRIPT_GENERATION("SCRIPT_GENERATION", "剧本生成", OUTLINE_DESIGN),

    /** 质量审核 */
    QUALITY_REVIEW("QUALITY_REVIEW", "质量审核", SCRIPT_GENERATION),

    /** 文档导出 */
    EXPORT("EXPORT", "文档导出", QUALITY_REVIEW),

    /** 完成 */
    DONE("DONE", "完成", EXPORT);

    private final String code;
    private final String displayName;
    private final WorkflowStep prerequisite;

    WorkflowStep(String code, String displayName, WorkflowStep prerequisite) {
        this.code = code;
        this.displayName = displayName;
        this.prerequisite = prerequisite;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public WorkflowStep getPrerequisite() {
        return prerequisite;
    }

    /**
     * 根据code查找枚举值.
     */
    public static WorkflowStep fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (WorkflowStep step : values()) {
            if (step.code.equalsIgnoreCase(code)) {
                return step;
            }
        }
        return null;
    }

    /**
     * 获取下一个步骤.
     */
    public WorkflowStep next() {
        int nextOrdinal = this.ordinal() + 1;
        WorkflowStep[] values = values();
        if (nextOrdinal < values.length) {
            return values[nextOrdinal];
        }
        return DONE;
    }

    /**
     * 是否已经是最终步骤.
     */
    public boolean isFinal() {
        return this == DONE;
    }

    /**
     * 检查是否满足前置条件.
     */
    public boolean hasPrerequisite(WorkflowStep step) {
        return this.prerequisite == step;
    }
}
