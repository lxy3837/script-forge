package com.erchuang.scriptforge.agent.script;

import com.erchuang.scriptforge.model.entity.ScriptChapter;

import java.util.List;

/**
 * 剧本拼接器——逐章结果拼接为完整剧本.
 *
 * @author ScriptForge Team
 */
public class ScriptAssembly {

    /**
     * 将章节列表拼接为完整剧本Markdown文本.
     *
     * @param title    剧本标题
     * @param chapters 章节列表
     * @return 完整剧本文本
     */
    public String assemble(String title, List<ScriptChapter> chapters) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("---\n\n");

        for (ScriptChapter chapter : chapters) {
            sb.append("## 第").append(chapter.getChapterNumber()).append("章 ")
                    .append(chapter.getTitle()).append("\n\n");

            if (chapter.getRawContent() != null) {
                sb.append(chapter.getRawContent()).append("\n\n");
            }

            sb.append("---\n\n");
        }

        return sb.toString();
    }
}
