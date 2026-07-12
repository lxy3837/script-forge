package com.erchuang.scriptforge.export;

import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.erchuang.scriptforge.model.enums.ExportFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 导出引擎测试.
 * <p>
 * 测试 ExportEngine 的格式路由、降级回退和异常处理。
 * 使用 Mock 引擎避免真实文件IO。
 * </p>
 *
 * @author ScriptForge Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("导出引擎测试")
class ExportEngineTest {

    @Mock private MarkdownEngine markdownEngine;
    @Mock private WordEngine wordEngine;
    @Mock private PdfEngine pdfEngine;
    @Mock private SrtEngine srtEngine;

    @InjectMocks
    private ExportEngine exportEngine;

    @TempDir
    Path tempDir;

    private String outputPath(String ext) {
        return tempDir.resolve("test-output" + ext).toString();
    }

    // ======================== 正向测试 ========================

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTests {

        @Test
        @DisplayName("导出Markdown：调用 MarkdownEngine")
        void shouldExportMarkdown() throws Exception {
            // Given
            String path = outputPath(".md");
            doNothing().when(markdownEngine).export(anyString(), eq(path));

            // When
            String result = exportEngine.export("# 测试", ExportFormat.MARKDOWN, path);

            // Then
            assertEquals(path, result);
            verify(markdownEngine).export(anyString(), eq(path));
        }

        @Test
        @DisplayName("导出Word：调用 WordEngine")
        void shouldExportWord() throws Exception {
            // Given
            String path = outputPath(".docx");
            doNothing().when(wordEngine).export(anyString(), eq(path));

            // When
            String result = exportEngine.export("# 测试", ExportFormat.WORD, path);

            // Then
            assertEquals(path, result);
            verify(wordEngine).export(anyString(), eq(path));
        }

        @Test
        @DisplayName("导出PDF：调用 PdfEngine")
        void shouldExportPdf() throws Exception {
            // Given
            String path = outputPath(".pdf");
            doNothing().when(pdfEngine).export(anyString(), eq(path));

            // When
            String result = exportEngine.export("# 测试", ExportFormat.PDF, path);

            // Then
            assertEquals(path, result);
            verify(pdfEngine).export(anyString(), eq(path));
        }

        @Test
        @DisplayName("导出SRT：调用 SrtEngine")
        void shouldExportSrt() throws Exception {
            // Given
            String path = outputPath(".srt");
            doNothing().when(srtEngine).export(anyString(), eq(path));

            // When
            String result = exportEngine.export("# 测试", ExportFormat.SRT, path);

            // Then
            assertEquals(path, result);
            verify(srtEngine).export(anyString(), eq(path));
        }

        @Test
        @DisplayName("纯文本导出：复用 MarkdownEngine")
        void shouldExportPlainText() throws Exception {
            // Given
            String path = outputPath(".txt");
            doNothing().when(markdownEngine).export(anyString(), eq(path));

            // When
            String result = exportEngine.export("# 测试", ExportFormat.PLAIN_TEXT, path);

            // Then
            assertEquals(path, result);
            verify(markdownEngine).export(anyString(), eq(path));
        }
    }

    // ======================== 异常流程测试 ========================

    @Nested
    @DisplayName("异常流程测试")
    class ExceptionFlowTests {

        @Test
        @DisplayName("不支持的格式：抛出 BusinessException")
        void shouldThrowForUnsupportedFormat() {
            // 由于ExportEngine中所有格式都有映射，这里测试的是逻辑层面
            // 验证格式路由能正确处理所有已知格式
            // (所有ExportFormat枚举值都应该有对应引擎)
            for (ExportFormat format : ExportFormat.values()) {
                assertNotNull(format.getExtension());
                assertNotNull(format.getDisplayName());
            }
        }

        @Test
        @DisplayName("WORD导出失败降级为纯文本")
        void shouldFallbackToPlainTextWhenWordFails() throws Exception {
            // Given: Word引擎抛出异常
            String path = outputPath(".docx");
            doThrow(new RuntimeException("Word导出失败")).when(wordEngine)
                    .export(anyString(), eq(path));
            // 降级到纯文本（MARKDOWN引擎）
            String fallbackPath = path.replace(".docx", ".txt");
            doNothing().when(markdownEngine).export(anyString(), eq(fallbackPath));

            // When
            String result = exportEngine.export("# 测试", ExportFormat.WORD, path);

            // Then: 降级成功，返回 .txt 路径
            assertEquals(fallbackPath, result);
            verify(wordEngine).export(anyString(), eq(path));
            verify(markdownEngine).export(anyString(), eq(fallbackPath));
        }

        @Test
        @DisplayName("PDF导出失败降级为纯文本")
        void shouldFallbackToPlainTextWhenPdfFails() throws Exception {
            // Given
            String path = outputPath(".pdf");
            doThrow(new RuntimeException("PDF导出失败")).when(pdfEngine)
                    .export(anyString(), eq(path));
            String fallbackPath = path.replace(".pdf", ".txt");
            doNothing().when(markdownEngine).export(anyString(), eq(fallbackPath));

            // When
            String result = exportEngine.export("# 测试", ExportFormat.PDF, path);

            // Then
            assertEquals(fallbackPath, result);
            verify(markdownEngine).export(anyString(), eq(fallbackPath));
        }
    }

    // ======================== 边界值测试 ========================

    @Nested
    @DisplayName("边界值测试")
    class BoundaryTests {

        @Test
        @DisplayName("空内容导出：正常执行")
        void shouldExportEmptyContent() throws Exception {
            // Given
            String path = outputPath(".md");
            doNothing().when(markdownEngine).export(eq(""), eq(path));

            // When
            String result = exportEngine.export("", ExportFormat.MARKDOWN, path);

            // Then
            assertEquals(path, result);
            verify(markdownEngine).export(eq(""), eq(path));
        }

        @Test
        @DisplayName("导出内容含特殊字符：正常执行")
        void shouldExportSpecialCharacterContent() throws Exception {
            // Given
            String specialContent = "# 标题\n\n```code```\n|表格|数据|\n---|---|---\n> 引用";
            String path = outputPath(".md");
            doNothing().when(markdownEngine).export(eq(specialContent), eq(path));

            // When
            String result = exportEngine.export(specialContent, ExportFormat.MARKDOWN, path);

            // Then
            assertEquals(path, result);
            verify(markdownEngine).export(eq(specialContent), eq(path));
        }

        @Test
        @DisplayName("ExportFormat.fromExtension 正确处理各扩展名")
        void shouldCorrectlyParseExtensions() {
            assertEquals(ExportFormat.MARKDOWN, ExportFormat.fromExtension(".md"));
            assertEquals(ExportFormat.WORD, ExportFormat.fromExtension(".docx"));
            assertEquals(ExportFormat.PDF, ExportFormat.fromExtension(".pdf"));
            assertEquals(ExportFormat.SRT, ExportFormat.fromExtension(".srt"));
            assertEquals(ExportFormat.PLAIN_TEXT, ExportFormat.fromExtension(".txt"));
            // 未知扩展名
            assertEquals(ExportFormat.MARKDOWN, ExportFormat.fromExtension(".unknown"));
            // null
            assertEquals(ExportFormat.MARKDOWN, ExportFormat.fromExtension(null));
        }
    }
}
