package com.erchuang.scriptforge.agent.outline;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.llm.TokenCounter;
import com.erchuang.scriptforge.model.entity.Outline;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.entity.Requirement;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.model.enums.ScopeLevel;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import com.erchuang.scriptforge.repository.OutlineRepository;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.RequirementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 大纲设计Agent测试.
 * <p>
 * 测试 OutlineAgent 的多版大纲生成、差异化检测和异常处理。
 * </p>
 *
 * @author ScriptForge Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("大纲设计Agent测试")
class OutlineAgentTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private RequirementRepository requirementRepository;
    @Mock private OutlineRepository outlineRepository;
    @Mock private DeepSeekClient deepSeekClient;
    @Mock private PromptTemplate promptTemplate;
    @Mock private TokenCounter tokenCounter;
    @Mock private SseEmitterService sseEmitterService;

    @InjectMocks
    private OutlineAgent outlineAgent;

    private static final Long PROJECT_ID = 1L;

    private Project buildProject() {
        return Project.builder()
                .id(PROJECT_ID).title("测试项目").gameName("原神")
                .status(ProjectStatus.IN_PROGRESS).build();
    }

    private Requirement buildRequirement() {
        return Requirement.builder()
                .id(1L).summaryContent("测试需求摘要")
                .targetCharacters("[\"钟离\"]")
                .stylePreference(WritingStyle.LIGHT_NOVEL)
                .scopeLevel(ScopeLevel.MEDIUM).build();
    }

    private List<String> buildThreeDifferentOutlines() {
        return List.of(
                "# 版本1 冒险故事\n## 故事梗概\n英雄踏上冒险之旅\n## 核心冲突\n内部挣扎\n## 情感走向\n由悲到喜\n## 章节划分\n第1章 启程",
                "# 版本2 浪漫邂逅\n## 故事梗概\n命运般的相遇\n## 核心冲突\n外部敌对势力\n## 情感走向\n温情脉脉\n## 章节划分\n第1章 相遇",
                "# 版本3 史诗战争\n## 故事梗概\n世界存亡之战\n## 核心冲突\n种族冲突\n## 情感走向\n悲壮史诗\n## 章节划分\n第1章 烽烟"
        );
    }

    @BeforeEach
    void setUp() {
        lenient().when(projectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(buildProject()));
        lenient().when(promptTemplate.load(anyString())).thenReturn("system prompt");
        lenient().when(tokenCounter.estimateTokens(anyString())).thenReturn(100);
    }

    // ======================== 正向测试 ========================

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTests {

        @Test
        @DisplayName("生成3版大纲：保存到数据库并返回3条记录")
        void shouldGenerateThreeOutlines() {
            // Given
            Requirement requirement = buildRequirement();
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(requirement));
            when(deepSeekClient.chat(anyList()))
                    .thenReturn(buildThreeDifferentOutlines().get(0))
                    .thenReturn(buildThreeDifferentOutlines().get(1))
                    .thenReturn(buildThreeDifferentOutlines().get(2));

            // When
            AgentResult result = outlineAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertEquals(3, result.getMetadata().get("outlineCount"));
            verify(outlineRepository).unselectAllByProjectId(PROJECT_ID);
            verify(outlineRepository, times(3)).save(any(Outline.class));
        }

        @Test
        @DisplayName("大纲标题提取：从 Markdown 标题行提取大纲标题")
        void shouldExtractTitleFromMarkdown() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(buildRequirement()));
            when(deepSeekClient.chat(anyList()))
                    .thenReturn("# 命运的重逢\n## 故事梗概\n主角们在璃月港重聚");

            // When
            AgentResult result = outlineAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            verify(outlineRepository).save(argThat(outline ->
                    "命运的重逢".equals(outline.getTitle())));
        }

        @Test
        @DisplayName("章节划分提取：从大纲文本中提取章节信息")
        void shouldExtractChapterSection() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(buildRequirement()));
            when(deepSeekClient.chat(anyList()))
                    .thenReturn("# 测试大纲\n## 故事梗概\n概要内容\n## 章节划分\n第1章 启程\n第2章 冒险\n第3章 决战");

            // When
            outlineAgent.execute(PROJECT_ID);

            // Then
            verify(outlineRepository).save(argThat(outline ->
                    outline.getChapters().contains("第1章") &&
                    outline.getChapters().contains("第2章")));
        }
    }

    // ======================== 异常流程测试 ========================

    @Nested
    @DisplayName("异常流程测试")
    class ExceptionFlowTests {

        @Test
        @DisplayName("需求未完成：返回 failure '请先完成需求调研'")
        void shouldFailWhenNoRequirement() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.empty());

            // When
            AgentResult result = outlineAgent.execute(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("请先完成需求调研"));
            verify(outlineRepository, never()).save(any());
        }

        @Test
        @DisplayName("DeepSeek API 异常：返回 failure")
        void shouldReturnFailureWhenDeepSeekFails() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(buildRequirement()));
            when(deepSeekClient.chat(anyList()))
                    .thenThrow(new RuntimeException("API调用失败"));

            // When
            AgentResult result = outlineAgent.execute(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("失败"));
        }

        @Test
        @DisplayName("项目不存在：返回 failure")
        void shouldReturnFailureWhenProjectNotFound() {
            // Given
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

            // When
            AgentResult result = outlineAgent.execute(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
        }
    }

    // ======================== 边界值测试 ========================

    @Nested
    @DisplayName("边界值测试")
    class BoundaryTests {

        @Test
        @DisplayName("需求 summaryContent 为空：正常执行")
        void shouldHandleEmptySummaryContent() {
            // Given
            Requirement emptyReq = Requirement.builder()
                    .id(1L).summaryContent("")
                    .stylePreference(WritingStyle.LIGHT_NOVEL)
                    .scopeLevel(ScopeLevel.MEDIUM).build();
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(emptyReq));
            when(deepSeekClient.chat(anyList()))
                    .thenReturn("# 大纲\n## 故事梗概\n基础内容");

            // When
            AgentResult result = outlineAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("stylePreference 为 null：不抛空指针，使用默认值")
        void shouldHandleNullStyle() {
            // Given
            Requirement nullStyleReq = Requirement.builder()
                    .id(1L).summaryContent("测试需求")
                    .stylePreference(null)
                    .scopeLevel(ScopeLevel.MEDIUM).build();
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(nullStyleReq));
            when(deepSeekClient.chat(anyList()))
                    .thenReturn("# 大纲\n## 故事梗概\n内容");

            // When
            AgentResult result = outlineAgent.execute(PROJECT_ID);

            // Then: 不抛空指针
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("大纲文本中无标题行：使用默认标题")
        void shouldUseDefaultTitleWhenNoHeadingFound() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(buildRequirement()));
            when(deepSeekClient.chat(anyList()))
                    .thenReturn("这是没有标题行的大纲内容\n故事梗概部分...");

            // When
            outlineAgent.execute(PROJECT_ID);

            // Then: 使用默认标题（项目标题 - 版本N）
            verify(outlineRepository).save(argThat(outline ->
                    outline.getTitle() != null && !outline.getTitle().isEmpty()));
        }
    }
}
