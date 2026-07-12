package com.erchuang.scriptforge.llm;

/**
 * 上下文类型——用于 TokenCounter 的智能截断策略选择.
 *
 * @author ScriptForge Team
 */
public enum ContextType {

    /** 叙事文本/对白：按句号、感叹号、换行等自然边界回溯截断 */
    NARRATIVE,

    /** JSON 结构化数据：按逗号、大括号/中括号等结构边界截断，保持 JSON 合法性 */
    JSON
}
