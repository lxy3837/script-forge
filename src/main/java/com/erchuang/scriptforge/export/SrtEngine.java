package com.erchuang.scriptforge.export;

import com.erchuang.scriptforge.infra.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRT字幕导出引擎——将剧本内容转换为SRT字幕格式.
 * <p>
 * 按段落拆分生成字幕条目，支持基本的时间码生成。
 * </p>
 *
 * @author ScriptForge Team
 */
@Component
public class SrtEngine implements ExportEngine.Engine {

    private static final Logger log = LoggerFactory.getLogger(SrtEngine.class);

    /** 对话模式：匹配 "角色名：台词" 或 "角色名:台词" */
    private static final Pattern DIALOG_PATTERN = Pattern.compile("^(.{1,10})[：:](.+)$");

    /** 每条字幕默认持续时间（秒） */
    private static final int DEFAULT_DURATION_SECONDS = 3;

    /** 字幕行最大字符数 */
    private static final int MAX_CHARS_PER_LINE = 42;

    @Override
    public void export(String content, String outputPath) {
        String filePath = ensureExtension(outputPath);
        String[] lines = content.split("\n");

        StringBuilder srt = new StringBuilder();
        int index = 1;
        int startSeconds = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("```")) {
                continue;
            }

            // 检查对话行
            Matcher matcher = DIALOG_PATTERN.matcher(trimmed);
            if (matcher.matches()) {
                String speaker = matcher.group(1).trim();
                String dialog = matcher.group(2).trim();

                // 超长台词分段
                if (dialog.length() > MAX_CHARS_PER_LINE) {
                    List<String> chunks = splitChunk(dialog, MAX_CHARS_PER_LINE);
                    for (String chunk : chunks) {
                        srt.append(formatEntry(index++, speaker + ": " + chunk,
                                startSeconds, DEFAULT_DURATION_SECONDS));
                        startSeconds += DEFAULT_DURATION_SECONDS;
                    }
                } else {
                    srt.append(formatEntry(index++, speaker + ": " + dialog,
                            startSeconds, DEFAULT_DURATION_SECONDS));
                    startSeconds += DEFAULT_DURATION_SECONDS;
                }
            } else if (trimmed.length() > 0) {
                // 叙述性文本
                int duration = Math.max(DEFAULT_DURATION_SECONDS,
                        trimmed.length() / 15);
                if (trimmed.length() > MAX_CHARS_PER_LINE) {
                    List<String> chunks = splitChunk(trimmed, MAX_CHARS_PER_LINE);
                    for (String chunk : chunks) {
                        srt.append(formatEntry(index++, chunk,
                                startSeconds, DEFAULT_DURATION_SECONDS));
                        startSeconds += DEFAULT_DURATION_SECONDS;
                    }
                } else {
                    srt.append(formatEntry(index++, trimmed, startSeconds, duration));
                    startSeconds += duration;
                }
            }
        }

        FileUtils.writeFileContent(filePath, srt.toString());
        log.debug("SRT exported to: {}", filePath);
    }

    private List<String> splitChunk(String text, int maxLen) {
        List<String> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += maxLen) {
            int end = Math.min(i + maxLen, text.length());
            chunks.add(text.substring(i, end));
        }
        return chunks;
    }

    /**
     * 格式化单条SRT字幕条目.
     *
     * @param index      序号
     * @param text       字幕文本
     * @param startSec   开始时间（秒）
     * @param durationSec 持续时间（秒）
     * @return SRT格式条目
     */
    private String formatEntry(int index, String text, int startSec, int durationSec) {
        int endSec = startSec + durationSec;
        return String.format("%d\n%s --> %s\n%s\n\n",
                index,
                secondsToSrtTime(startSec),
                secondsToSrtTime(endSec),
                text);
    }

    /**
     * 秒数转SRT时间码格式 (HH:MM:SS,mmm).
     */
    private String secondsToSrtTime(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d,000", hours, minutes, seconds);
    }

    private String ensureExtension(String outputPath) {
        if (!outputPath.toLowerCase().endsWith(".srt")) {
            return outputPath + ".srt";
        }
        return outputPath;
    }
}
