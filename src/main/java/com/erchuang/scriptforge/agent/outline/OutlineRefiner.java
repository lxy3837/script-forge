package com.erchuang.scriptforge.agent.outline;

/**
 * 大纲微调器——处理用户微调指令，局部更新大纲.
 *
 * @author ScriptForge Team
 */
public class OutlineRefiner {

    /**
     * 根据用户的微调指令更新大纲.
     *
     * @param existingOutline 现有大纲文本
     * @param refineInstruction 微调指令
     * @return 更新后的大纲文本
     */
    public String refine(String existingOutline, String refineInstruction) {
        // 简化实现：将指令附到大纲末尾作为修改记录
        if (existingOutline == null) {
            return "# 微调后的大纲\n\n" + refineInstruction;
        }
        return existingOutline + "\n\n---\n\n## 用户微调\n\n" + refineInstruction;
    }
}
