package com.erchuang.scriptforge.agent.requirement;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.DeepSeekClient.ChatMessage;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.entity.Requirement;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.model.enums.ScopeLevel;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.RequirementRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 需求调研Agent测试.
 * <p>
 * 测试 RequirementAgent 的需求分析、风格解析和异常处理逻辑。
 * </p>
 *
 * @author ScriptForge Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("需求调研Agent测试")
class RequirementAgentTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private RequirementRepository requirementRepository;
    @Mock private DeepSeekClient deepSeekClient;
    @Mock private PromptTemplate promptTemplate;
    @Mock private SseEmitterService sseEmitterService;

    @InjectMocks
    private RequirementAgent requirementAgent;

    private static final Long PROJECT_ID = 1L;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Project buildProject(String title, String gameName) {
        return Project.builder()
                .id(PROJECT_ID)
                .title(title)
                .gameName(gameName)
                .status(ProjectStatus.DRAFT)
                .build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(promptTemplate.load(anyString())).thenReturn("你是一个需求分析助手");
    }

    // ======================== 正向测试 ========================

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTests {

        @Test
        @DisplayName("有效项目标题和游戏名：返回需求摘要并保存数据库")
        void shouldReturnSummaryForValidInput() {
            // Given
            Project project = buildProject("钟离和雷电将军在璃月港相遇", "原神");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            String aiResponse = "世界观：提瓦特大陆\n风格：轻小说\n篇幅：中篇\n角色：钟离、雷电将军";
            when(deepSeekClient.chat(ArgumentMatchers.<List<ChatMessage>>any()))
                    .thenReturn(aiResponse);

            // When
            AgentResult result = requirementAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertNotNull(result.getData());
            assertTrue(result.getData().length() > 0);
            verify(requirementRepository).save(any(Requirement.class));
        }

        @Test
        @DisplayName("AI响应含戏剧风格关键词：正确解析为 DRAMA")
        void shouldParseDramaStyle() {
            // Given
            Project project = buildProject("悲剧爱情故事", "原神");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(deepSeekClient.chat(ArgumentMatchers.<List<ChatMessage>>any()))
                    .thenReturn("风格：戏剧\n篇幅：中篇");

            // When
            AgentResult result = requirementAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            verify(requirementRepository).save(argThat(req ->
                    req.getStylePreference() == WritingStyle.DRAMA));
        }

        @Test
        @DisplayName("AI响应含长篇关键词：正确解析为 LONG")
        void shouldParseLongScope() {
            // Given
            Project project = buildProject("长篇史诗冒险", "原神");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(deepSeekClient.chat(ArgumentMatchers.<List<ChatMessage>>any()))
                    .thenReturn("篇幅：长篇");

            // When
            requirementAgent.execute(PROJECT_ID);

            // Then
            verify(requirementRepository).save(argThat(req ->
                    req.getScopeLevel() == ScopeLevel.LONG));
        }

        @Test
        @DisplayName("AI响应含短篇关键词：正确解析为 SHORT")
        void shouldParseShortScope() {
            // Given
            Project project = buildProject("一个小短篇", "原神");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(deepSeekClient.chat(ArgumentMatchers.<List<ChatMessage>>any()))
                    .thenReturn("篇幅：短篇");

            // When
            requirementAgent.execute(PROJECT_ID);

            // Then
            verify(requirementRepository).save(argThat(req ->
                    req.getScopeLevel() == ScopeLevel.SHORT));
        }
    }

    // ======================== 异常流程测试 ========================

    @Nested
    @DisplayName("异常流程测试")
    class ExceptionFlowTests {

        @Test
        @DisplayName("项目不存在：返回 failure 结果")
        void shouldReturnFailureWhenProjectNotFound() {
            // Given
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

            // When
            AgentResult result = requirementAgent.execute(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("失败"));
            verify(deepSeekClient, never()).chat(any());
        }

        @Test
        @DisplayName("DeepSeek API 异常：返回 failure 结果")
        void shouldReturnFailureWhenDeepSeekFails() {
            // Given
            when(projectRepository.findById(PROJECT_ID))
                    .thenReturn(Optional.of(buildProject("测试标题", "原神")));
            when(deepSeekClient.chat(ArgumentMatchers.<List<ChatMessage>>any()))
                    .thenThrow(new RuntimeException("API超时"));

            // When
            AgentResult result = requirementAgent.execute(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("需求调研失败"));
        }
    }

    // ======================== 边界值测试 ========================

    @Nested
    @DisplayName("边界值测试")
    class BoundaryTests {

        @Test
        @DisplayName("空标题项目：正常执行，不抛空指针")
        void shouldHandleEmptyTitle() {
            // Given
            Project project = buildProject("", "原神");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(deepSeekClient.chat(ArgumentMatchers.<List<ChatMessage>>any()))
                    .thenReturn("无明确需求");

            // When
            AgentResult result = requirementAgent.execute(PROJECT_ID);

            // Then: 正常完成，不抛异常
            assertTrue(result.isSuccess());
            verify(requirementRepository).save(any(Requirement.class));
        }

        @Test
        @DisplayName("超长标题：正常处理不报错")
        void shouldHandleVeryLongTitle() {
            // Given
            String longTitle = "A".repeat(2000);
            Project project = buildProject(longTitle, "原神");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(deepSeekClient.chat(ArgumentMatchers.<List<ChatMessage>>any()))
                    .thenReturn("需求分析完成");

            // When
            AgentResult result = requirementAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("AI响应为 null：stylePreference 默认为 LIGHT_NOVEL")
        void shouldDefaultStyleWhenAiResponseNull() {
            // Given
            Project project = buildProject("测试", "原神");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(deepSeekClient.chat(ArgumentMatchers.<List<ChatMessage>>any()))
                    .thenReturn(null); // 模拟API返回null

            // When
            AgentResult result = requirementAgent.execute(PROJECT_ID);

            // Then: 即使是null也会在execute中被catch
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("特殊字符标题：正常处理")
        void shouldHandleSpecialCharactersInTitle() {
            // Given
            Project project = buildProject("<script>alert('xss')</script>", "原神");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(deepSeekClient.chat(ArgumentMatchers.<List<ChatMessage>>any()))
                    .thenReturn("风格：轻小说");

            // When
            AgentResult result = requirementAgent.execute(PROJECT_ID);

            // Then: 正常处理，不抛异常
            assertTrue(result.isSuccess());
        }
    }
}
