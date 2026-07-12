package com.erchuang.scriptforge.agent.script;

import com.erchuang.scriptforge.model.enums.WritingStyle;

/**
 * 文风适配器——根据用户选择的风格调整prompt参数.
 *
 * @author ScriptForge Team
 */
public class StyleAdapter {

    /**
     * 获取风格对应的prompt指导参数.
     *
     * @param style 写作风格
     * @return 风格提示文本
     */
    public String getStylePrompt(WritingStyle style) {
        if (style == null) return getDefaultStylePrompt();
        return switch (style) {
            case LIGHT_NOVEL -> """
                    写作风格：轻小说
                    - 使用口语化、轻松的叙述语气
                    - 适度加入角色内心独白
                    - 段落较短，便于阅读
                    - 适当使用夸张和萌元素描写
                    """;
            case DRAMA -> """
                    写作风格：戏剧
                    - 对话为主，叙述为辅
                    - 强调戏剧冲突和张力的营造
                    - 人物对话具有舞台感
                    - 场景转换使用幕/场标记
                    """;
            case NOVEL -> """
                    写作风格：小说体
                    - 使用文学化的描写语言
                    - 重视环境烘托和心理描写
                    - 段落较长，注重细节刻画
                    - 可采用多视角交替叙事
                    """;
            case SCRIPT -> """
                    写作风格：脚本/台词体
                    - 以对话台本为主
                    - 简短的场景说明
                    - 角色名：台词对白 格式
                    - 适合配音/制作视频
                    """;
        };
    }

    private String getDefaultStylePrompt() {
        return getStylePrompt(WritingStyle.LIGHT_NOVEL);
    }
}
