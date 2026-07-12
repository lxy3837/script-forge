package com.erchuang.scriptforge.agent.document;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 版本差异对比引擎——对比两个版本内容，生成增量diff.
 *
 * @author ScriptForge Team
 */
@Component
public class DiffEngine {

    /**
     * 对比两个文本并生成差异报告.
     *
     * @param oldContent 旧版本内容
     * @param newContent 新版本内容
     * @return diff报告
     */
    public String computeDiff(String oldContent, String newContent) {
        if (oldContent == null && newContent == null) return "两者均为空";
        if (oldContent == null) return "[新增] " + truncate(newContent, 200);
        if (newContent == null) return "[删除] " + truncate(oldContent, 200);

        // 简化实现：逐行对比
        String[] oldLines = oldContent.split("\n");
        String[] newLines = newContent.split("\n");

        List<String> diffLines = new ArrayList<>();
        int maxLen = Math.max(oldLines.length, newLines.length);

        for (int i = 0; i < maxLen; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : null;
            String newLine = i < newLines.length ? newLines[i] : null;

            if (oldLine == null) {
                diffLines.add("+ " + newLine);
            } else if (newLine == null) {
                diffLines.add("- " + oldLine);
            } else if (!oldLine.equals(newLine)) {
                diffLines.add("- " + oldLine);
                diffLines.add("+ " + newLine);
            }
        }

        return String.join("\n", diffLines);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
