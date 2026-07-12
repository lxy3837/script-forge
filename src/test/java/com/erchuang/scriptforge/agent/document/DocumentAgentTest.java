package com.erchuang.scriptforge.agent.document;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.export.ExportEngine;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.entity.Script;
import com.erchuang.scriptforge.model.entity.ScriptChapter;
import com.erchuang.scriptforge.model.enums.ExportFormat;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.ScriptChapterRepository;
import com.erchuang.scriptforge.repository.ScriptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 文档编辑Agent测试.
 * <p>
 * 测试 DocumentAgent 的多格式导出、异常处理和降级逻辑。
 * </p>
 *
 * @author ScriptForge Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文档编辑Agent测试")
class DocumentAgentTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ScriptRepository scriptRepository;
    @Mock private ScriptChapterRepository chapterRepository;
    @Mock private ExportEngine exportEngine;
    @Mock private SseEmitterService sseEmitterService;

    @InjectMocks
    private DocumentAgent documentAgent;

    private static final Long PROJECT_ID = 1L;

    @BeforeEach
    void setUp() {
        // 设置 exportDir 的值（通过 ReflectionTestUtils 注入 @Value）
        ReflectionTestUtils.setField(documentAgent, "exportDir",
                System.getProperty("java.io.tmpdir") + "/script-forge-test-exports");
    }

    private Project buildProject() {
        return Project.builder()
                .id(PROJECT_ID).title("测试文档项目").gameName("原神")
                .status(ProjectStatus.COMPLETED).build();
    }

    private Script buildScript() {
        return Script.builder()
                .id(200L).title("测试剧本").build();
    }

    private List<ScriptChapter> buildChapters() {
        return List.of(
                ScriptChapter.builder()
                        .id(1L).chapterNumber(1).title("第一章")
                        .rawContent("这是第一章的内容").sceneCount(2).build(),
                ScriptChapter.builder()
                        .id(2L).chapterNumber(2).title("第二章")
                        .rawContent("这是第二章的内容").sceneCount(1).build()
        );
    }

    private void setupFullScriptContext() {
        Project project = buildProject();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        Script script = buildScript();
        script.setProject(project);
        when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(script));

        when(chapterRepository.findByScriptIdOrderByChapterNumberAsc(200L))
                .thenReturn(buildChapters());
    }

    // ======================== 正向测试 ========================

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTests {

        @Test
        @DisplayName("导出Markdown：默认格式导出成功")
        void shouldExportMarkdownByDefault() {
            // Given
            setupFullScriptContext();
            when(exportEngine.export(anyString(), eq(ExportFormat.MARKDOWN), anyString()))
                    .thenReturn("/tmp/test.md");

            // When
            AgentResult result = documentAgent.exportDocument(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertTrue(result.getData().contains("已导出"));
            assertEquals("/tmp/test.md", result.getMetadata().get("exportPath"));
            assertEquals("MARKDOWN", result.getMetadata().get("format"));
        }

        @Test
        @DisplayName("导出Word：指定格式为 WORD")
        void shouldExportWord() {
            // Given
            setupFullScriptContext();
            when(exportEngine.export(anyString(), eq(ExportFormat.WORD), anyString()))
                    .thenReturn("/tmp/test.docx");

            // When
            AgentResult result = documentAgent.exportDocument(PROJECT_ID, ExportFormat.WORD);

            // Then
            assertTrue(result.isSuccess());
            verify(exportEngine).export(anyString(), eq(ExportFormat.WORD), anyString());
        }

        @Test
        @DisplayName("导出PDF：指定格式为 PDF")
        void shouldExportPdf() {
            // Given
            setupFullScriptContext();
            when(exportEngine.export(anyString(), eq(ExportFormat.PDF), anyString()))
                    .thenReturn("/tmp/test.pdf");

            // When
            AgentResult result = documentAgent.exportDocument(PROJECT_ID, ExportFormat.PDF);

            // Then
            assertTrue(result.isSuccess());
            verify(exportEngine).export(anyString(), eq(ExportFormat.PDF), anyString());
        }

        @Test
        @DisplayName("导出SRT：指定格式为 SRT")
        void shouldExportSrt() {
            // Given
            setupFullScriptContext();
            when(exportEngine.export(anyString(), eq(ExportFormat.SRT), anyString()))
                    .thenReturn("/tmp/test.srt");

            // When
            AgentResult result = documentAgent.exportDocument(PROJECT_ID, ExportFormat.SRT);

            // Then
            assertTrue(result.isSuccess());
            verify(exportEngine).export(anyString(), eq(ExportFormat.SRT), anyString());
        }
    }

    // ======================== 异常流程测试 ========================

    @Nested
    @DisplayName("异常流程测试")
    class ExceptionFlowTests {

        @Test
        @DisplayName("项目不存在：返回 failure")
        void shouldFailWhenProjectNotFound() {
            // Given
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

            // When
            AgentResult result = documentAgent.exportDocument(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("失败"));
        }

        @Test
        @DisplayName("无剧本可导出：返回 failure '没有可导出的剧本'")
        void shouldFailWhenNoScriptAvailable() {
            // Given
            when(projectRepository.findById(PROJECT_ID))
                    .thenReturn(Optional.of(buildProject()));
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of());

            // When
            AgentResult result = documentAgent.exportDocument(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("没有可导出的剧本"));
        }

        @Test
        @DisplayName("导出引擎异常：返回 failure")
        void shouldFailWhenExportEngineThrows() {
            // Given
            setupFullScriptContext();
            when(exportEngine.export(anyString(), any(ExportFormat.class), anyString()))
                    .thenThrow(new RuntimeException("导出引擎错误"));

            // When
            AgentResult result = documentAgent.exportDocument(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("失败"));
        }
    }

    // ======================== 边界值测试 ========================

    @Nested
    @DisplayName("边界值测试")
    class BoundaryTests {

        @Test
        @DisplayName("章节 rawContent 为 null：不追加 null 内容")
        void shouldHandleNullRawContent() {
            // Given
            Project project = buildProject();
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

            Script script = buildScript();
            script.setProject(project);
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(script));

            // 一个章节的 rawContent 为 null
            ScriptChapter nullContentChapter = ScriptChapter.builder()
                    .id(1L).chapterNumber(1).title("空内容章节")
                    .rawContent(null).sceneCount(0).build();
            when(chapterRepository.findByScriptIdOrderByChapterNumberAsc(200L))
                    .thenReturn(List.of(nullContentChapter));

            when(exportEngine.export(anyString(), any(ExportFormat.class), anyString()))
                    .thenReturn("/tmp/test-null.md");

            // When
            AgentResult result = documentAgent.exportDocument(PROJECT_ID);

            // Then: 不抛空指针，正常执行
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("无章节的剧本：拼接为仅含标题的内容")
        void shouldHandleScriptWithNoChapters() {
            // Given
            Project project = buildProject();
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

            Script script = buildScript();
            script.setProject(project);
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(script));
            when(chapterRepository.findByScriptIdOrderByChapterNumberAsc(200L))
                    .thenReturn(List.of());

            when(exportEngine.export(anyString(), any(ExportFormat.class), anyString()))
                    .thenReturn("/tmp/test-empty.md");

            // When
            AgentResult result = documentAgent.exportDocument(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
        }
    }
}
