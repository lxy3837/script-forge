package com.erchuang.scriptforge.agent.script;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.llm.TokenCounter;
import com.erchuang.scriptforge.model.entity.Outline;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.entity.Requirement;
import com.erchuang.scriptforge.model.entity.Script;
import com.erchuang.scriptforge.model.entity.ScriptChapter;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.model.enums.ScopeLevel;
import com.erchuang.scriptforge.model.enums.ScriptStatus;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import com.erchuang.scriptforge.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 剧本创作Agent测试.
 * <p>
 * 测试 ScriptAgent 的逐章剧本生成、风格适配和异常处理。
 * </p>
 *
 * @author ScriptForge Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("剧本创作Agent测试")
class ScriptAgentTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private RequirementRepository requirementRepository;
    @Mock private OutlineRepository outlineRepository;
    @Mock private ScriptRepository scriptRepository;
    @Mock private ScriptChapterRepository chapterRepository;
    @Mock private DeepSeekClient deepSeekClient;
    @Mock private PromptTemplate promptTemplate;
    @Mock private TokenCounter tokenCounter;
    @Mock private SseEmitterService sseEmitterService;

    @InjectMocks
    private ScriptAgent scriptAgent;

    private static final Long PROJECT_ID = 1L;

    private Project buildProject() {
        return Project.builder()
                .id(PROJECT_ID).title("测试剧本").gameName("原神")
                .status(ProjectStatus.IN_PROGRESS).build();
    }

    private Outline buildSelectedOutline() {
        return Outline.builder()
                .id(10L).versionNumber(1).title("选定大纲")
                .summary("测试摘要").coreConflict("核心冲突")
                .emotionalArc("情感走向").chapters("章节列表")
                .selected(true).build();
    }

    private Requirement buildRequirement(WritingStyle style, ScopeLevel scope) {
        return Requirement.builder()
                .id(1L).summaryContent("需求摘要")
                .stylePreference(style)
                .scopeLevel(scope).build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(projectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(buildProject()));
        lenient().when(promptTemplate.load(anyString())).thenReturn("system prompt");
        lenient().when(tokenCounter.estimateTokens(anyString())).thenReturn(500);
        lenient().when(tokenCounter.mayExceedLimit(anyString())).thenReturn(false);
        lenient().when(scriptRepository.save(any(Script.class))).thenAnswer(inv -> {
            Script s = inv.getArgument(0);
            if (s.getId() == null) s.setId(100L);
            return s;
        });
    }

    // ======================== 正向测试 ========================

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTests {

        @Test
        @DisplayName("生成中篇剧本：scope=MEDIUM 生成5章")
        void shouldGenerateMediumScript() {
            // Given
            Requirement requirement = buildRequirement(WritingStyle.LIGHT_NOVEL, ScopeLevel.MEDIUM);
            Outline selectedOutline = buildSelectedOutline();
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(requirement));
            when(outlineRepository.findByProjectIdAndSelectedTrue(PROJECT_ID))
                    .thenReturn(Optional.of(selectedOutline));
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("# 第N章\n场景1：开始冒险");

            // When
            AgentResult result = scriptAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertEquals(5, result.getMetadata().get("totalChapters"));
            verify(chapterRepository, times(5)).save(any(ScriptChapter.class));
            verify(scriptRepository, times(2)).save(any(Script.class)); // 创建+更新
        }

        @Test
        @DisplayName("生成短篇剧本：scope=SHORT 生成3章")
        void shouldGenerateShortScript() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(buildRequirement(WritingStyle.LIGHT_NOVEL, ScopeLevel.SHORT)));
            when(outlineRepository.findByProjectIdAndSelectedTrue(PROJECT_ID))
                    .thenReturn(Optional.of(buildSelectedOutline()));
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("# 章节\n场景内容");

            // When
            AgentResult result = scriptAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertEquals(3, result.getMetadata().get("totalChapters"));
            verify(chapterRepository, times(3)).save(any(ScriptChapter.class));
        }

        @Test
        @DisplayName("风格适配：传入 DRAMA 风格到生成器")
        void shouldUseDramaStyle() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(buildRequirement(WritingStyle.DRAMA, ScopeLevel.SHORT)));
            when(outlineRepository.findByProjectIdAndSelectedTrue(PROJECT_ID))
                    .thenReturn(Optional.of(buildSelectedOutline()));
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("# 章节\n戏剧风格内容");

            // When
            AgentResult result = scriptAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            verify(scriptRepository, atLeastOnce()).save(argThat(script ->
                    script.getWritingStyle() == WritingStyle.DRAMA));
        }
    }

    // ======================== 异常流程测试 ========================

    @Nested
    @DisplayName("异常流程测试")
    class ExceptionFlowTests {

        @Test
        @DisplayName("大写未选定：返回 failure '请先选定大纲'")
        void shouldFailWhenNoSelectedOutline() {
            // Given
            when(outlineRepository.findByProjectIdAndSelectedTrue(PROJECT_ID))
                    .thenReturn(Optional.empty());

            // When
            AgentResult result = scriptAgent.execute(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("请先选定大纲"));
            verify(scriptRepository, never()).save(any());
        }

        @Test
        @DisplayName("项目不存在：返回 failure")
        void shouldFailWhenProjectNotFound() {
            // Given
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

            // When
            AgentResult result = scriptAgent.execute(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("DeepSeek API 异常：返回 failure")
        void shouldReturnFailureWhenDeepSeekFails() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(buildRequirement(WritingStyle.LIGHT_NOVEL, ScopeLevel.SHORT)));
            when(outlineRepository.findByProjectIdAndSelectedTrue(PROJECT_ID))
                    .thenReturn(Optional.of(buildSelectedOutline()));
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenThrow(new RuntimeException("API异常"));

            // When
            AgentResult result = scriptAgent.execute(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
        }
    }

    // ======================== 边界值测试 ========================

    @Nested
    @DisplayName("边界值测试")
    class BoundaryTests {

        @Test
        @DisplayName("requirement 为 null：使用默认 style 和 scope")
        void shouldUseDefaultsWhenNoRequirement() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.empty());
            when(outlineRepository.findByProjectIdAndSelectedTrue(PROJECT_ID))
                    .thenReturn(Optional.of(buildSelectedOutline()));
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("# 章节\n内容");

            // When
            AgentResult result = scriptAgent.execute(PROJECT_ID);

            // Then: LIGHT_NOVEL + MEDIUM (5章)
            assertTrue(result.isSuccess());
            verify(scriptRepository, atLeastOnce()).save(argThat(script ->
                    script.getWritingStyle() == WritingStyle.LIGHT_NOVEL));
            assertEquals(5, result.getMetadata().get("totalChapters"));
        }

        @Test
        @DisplayName("长篇剧本：scope=LONG 生成10章")
        void shouldGenerateLongScript() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(buildRequirement(WritingStyle.NOVEL, ScopeLevel.LONG)));
            when(outlineRepository.findByProjectIdAndSelectedTrue(PROJECT_ID))
                    .thenReturn(Optional.of(buildSelectedOutline()));
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("# 章节\n长篇内容");

            // When
            AgentResult result = scriptAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertEquals(10, result.getMetadata().get("totalChapters"));
            verify(chapterRepository, times(10)).save(any(ScriptChapter.class));
        }

        @Test
        @DisplayName("全流程数据完整性：验证 script、fullScript 和 metadata")
        void shouldHaveCompleteDataInResult() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(buildRequirement(WritingStyle.LIGHT_NOVEL, ScopeLevel.SHORT)));
            when(outlineRepository.findByProjectIdAndSelectedTrue(PROJECT_ID))
                    .thenReturn(Optional.of(buildSelectedOutline()));
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("第一章剧情内容...");

            // When
            AgentResult result = scriptAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertNotNull(result.getData());
            assertNotNull(result.getMetadata().get("scriptId"));
            assertNotNull(result.getMetadata().get("fullScript"));
            assertTrue(((String) result.getMetadata().get("fullScript")).contains("# "));
        }
    }
}
