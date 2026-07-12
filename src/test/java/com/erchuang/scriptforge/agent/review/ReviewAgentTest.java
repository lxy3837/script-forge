package com.erchuang.scriptforge.agent.review;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.entity.ReviewReport;
import com.erchuang.scriptforge.model.entity.Script;
import com.erchuang.scriptforge.model.entity.ScriptChapter;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.repository.ReviewReportRepository;
import com.erchuang.scriptforge.repository.ScriptChapterRepository;
import com.erchuang.scriptforge.repository.ScriptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 质量审核Agent测试.
 * <p>
 * 测试 ReviewAgent 的三维度审核（OOC/逻辑/节奏）和评分计算。
 * </p>
 *
 * @author ScriptForge Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("质量审核Agent测试")
class ReviewAgentTest {

    @Mock private ScriptRepository scriptRepository;
    @Mock private ScriptChapterRepository chapterRepository;
    @Mock private ReviewReportRepository reviewReportRepository;
    @Mock private DeepSeekClient deepSeekClient;
    @Mock private PromptTemplate promptTemplate;
    @Mock private SseEmitterService sseEmitterService;

    @InjectMocks
    private ReviewAgent reviewAgent;

    private static final Long PROJECT_ID = 1L;

    private Project buildProject() {
        return Project.builder()
                .id(PROJECT_ID).title("测试项目").gameName("原神")
                .status(ProjectStatus.IN_PROGRESS).build();
    }

    private Script buildScript() {
        Script script = Script.builder()
                .id(200L).title("测试剧本").build();
        script.setProject(buildProject());
        return script;
    }

    private List<ScriptChapter> buildChapters() {
        return List.of(
                ScriptChapter.builder()
                        .id(1L).chapterNumber(1).title("第一章")
                        .rawContent("## 第1章\n场景1：主角登场\n台词：\"我来了\"")
                        .sceneCount(2).build(),
                ScriptChapter.builder()
                        .id(2L).chapterNumber(2).title("第二章")
                        .rawContent("## 第2章\n场景1：冲突爆发\n台词：\"为什么\"")
                        .sceneCount(1).build()
        );
    }

    @BeforeEach
    void setUp() {
        lenient().when(promptTemplate.load(anyString())).thenReturn("审核系统提示");
    }

    // ======================== 正向测试 ========================

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTests {

        @Test
        @DisplayName("三项审核全部通过：score >= 75")
        void shouldPassAllThreeReviews() {
            // Given
            Script script = buildScript();
            List<ScriptChapter> chapters = buildChapters();
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(script));
            when(chapterRepository.findByScriptIdOrderByChapterNumberAsc(200L))
                    .thenReturn(chapters);
            // 模拟审核返回 "无问题" 的结果
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("审核通过")
                    .thenReturn("逻辑一致")
                    .thenReturn("节奏良好");

            // When
            AgentResult result = reviewAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertNotNull(result.getMetadata().get("score"));
            BigDecimal score = (BigDecimal) result.getMetadata().get("score");
            assertTrue(score.compareTo(new BigDecimal("75.00")) >= 0);
            verify(reviewReportRepository).save(any(ReviewReport.class));
        }

        @Test
        @DisplayName("OOC检测到严重问题：扣15分")
        void shouldDeductScoreForOOCIssue() {
            // Given
            Script script = buildScript();
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(script));
            when(chapterRepository.findByScriptIdOrderByChapterNumberAsc(200L))
                    .thenReturn(buildChapters());
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("检测到严重OOC偏离：角色行为不符合官方设定")
                    .thenReturn("逻辑一致")
                    .thenReturn("节奏良好");

            // When
            AgentResult result = reviewAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            BigDecimal score = (BigDecimal) result.getMetadata().get("score");
            assertTrue(score.compareTo(new BigDecimal("75.00")) < 0);
        }

        @Test
        @DisplayName("逻辑检测到严重矛盾：扣10分")
        void shouldDeductScoreForLogicIssue() {
            // Given
            Script script = buildScript();
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(script));
            when(chapterRepository.findByScriptIdOrderByChapterNumberAsc(200L))
                    .thenReturn(buildChapters());
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("审核通过")
                    .thenReturn("检测到严重矛盾：时间线冲突")
                    .thenReturn("节奏良好");

            // When
            AgentResult result = reviewAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            BigDecimal score = (BigDecimal) result.getMetadata().get("score");
            assertTrue(score.compareTo(new BigDecimal("75.00")) < 0);
        }

        @Test
        @DisplayName("节奏检测到问题：扣8分")
        void shouldDeductScoreForPacingIssue() {
            // Given
            Script script = buildScript();
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(script));
            when(chapterRepository.findByScriptIdOrderByChapterNumberAsc(200L))
                    .thenReturn(buildChapters());
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("审核通过")
                    .thenReturn("逻辑一致")
                    .thenReturn("节奏问题：后半段过于拖沓");

            // When
            AgentResult result = reviewAgent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            BigDecimal score = (BigDecimal) result.getMetadata().get("score");
            assertTrue(score.compareTo(new BigDecimal("75.00")) < 0);
        }
    }

    // ======================== 异常流程测试 ========================

    @Nested
    @DisplayName("异常流程测试")
    class ExceptionFlowTests {

        @Test
        @DisplayName("无剧本：返回 failure '没有可审核的剧本'")
        void shouldFailWhenNoScript() {
            // Given
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of());

            // When
            AgentResult result = reviewAgent.execute(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("没有可审核的剧本"));
            verify(reviewReportRepository, never()).save(any());
        }

        @Test
        @DisplayName("空章节列表：正常执行审核")
        void shouldHandleEmptyChapters() {
            // Given
            Script script = buildScript();
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(script));
            when(chapterRepository.findByScriptIdOrderByChapterNumberAsc(200L))
                    .thenReturn(List.of());
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("无内容")
                    .thenReturn("无内容")
                    .thenReturn("无内容");

            // When
            AgentResult result = reviewAgent.execute(PROJECT_ID);

            // Then: 空章节可正常执行（buildFullScript返回空字符串）
            assertTrue(result.isSuccess());
            verify(reviewReportRepository).save(any(ReviewReport.class));
        }
    }

    // ======================== 边界值测试 ========================

    @Nested
    @DisplayName("边界值测试")
    class BoundaryTests {

        @Test
        @DisplayName("score 不低于10.00：全部严重失败时bottoming out")
        void shouldHaveMinimumScore() {
            // Given
            Script script = buildScript();
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(script));
            when(chapterRepository.findByScriptIdOrderByChapterNumberAsc(200L))
                    .thenReturn(buildChapters());
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("严重OOC偏离：多处角色行为不符")
                    .thenReturn("严重矛盾：多处逻辑矛盾")
                    .thenReturn("节奏问题：全篇节奏混乱");

            // When
            AgentResult result = reviewAgent.execute(PROJECT_ID);

            // Then: 即使全部扣分，score >= 10.00
            BigDecimal score = (BigDecimal) result.getMetadata().get("score");
            assertTrue(score.compareTo(new BigDecimal("10.00")) >= 0,
                    "score should be >= 10.00 but was " + score);
        }

        @Test
        @DisplayName("score 不超过100.00：全部良好时topping out")
        void shouldHaveMaximumScore() {
            // Given
            Script script = buildScript();
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(script));
            when(chapterRepository.findByScriptIdOrderByChapterNumberAsc(200L))
                    .thenReturn(buildChapters());
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("审核通过")
                    .thenReturn("逻辑一致")
                    .thenReturn("节奏良好");

            // When
            AgentResult result = reviewAgent.execute(PROJECT_ID);

            // Then
            BigDecimal score = (BigDecimal) result.getMetadata().get("score");
            assertTrue(score.compareTo(new BigDecimal("100.01")) < 0,
                    "score should be <= 100.00 but was " + score);
        }

        @Test
        @DisplayName("审核报告包含 metadata 中的 reportId")
        void shouldIncludeReportIdInMetadata() {
            // Given
            Script script = buildScript();
            when(scriptRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(script));
            when(chapterRepository.findByScriptIdOrderByChapterNumberAsc(200L))
                    .thenReturn(buildChapters());
            when(deepSeekClient.chat(ArgumentMatchers.<List<DeepSeekClient.ChatMessage>>any()))
                    .thenReturn("通过").thenReturn("通过").thenReturn("通过");
            when(reviewReportRepository.save(any(ReviewReport.class))).thenAnswer(inv -> {
                ReviewReport r = inv.getArgument(0);
                r.setId(300L);
                return r;
            });

            // When
            AgentResult result = reviewAgent.execute(PROJECT_ID);

            // Then
            assertEquals(300L, result.getMetadata().get("reportId"));
        }
    }
}
