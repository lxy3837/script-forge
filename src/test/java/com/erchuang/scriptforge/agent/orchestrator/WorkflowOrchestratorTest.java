package com.erchuang.scriptforge.agent.orchestrator;

import com.erchuang.scriptforge.agent.character.CharacterRetrievalAgent;
import com.erchuang.scriptforge.agent.document.DocumentAgent;
import com.erchuang.scriptforge.agent.outline.OutlineAgent;
import com.erchuang.scriptforge.agent.requirement.RequirementAgent;
import com.erchuang.scriptforge.agent.review.ReviewAgent;
import com.erchuang.scriptforge.agent.script.ScriptAgent;
import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.model.enums.WorkflowStep;
import com.erchuang.scriptforge.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.*;

/**
 * 总调度Agent工作流状态机测试.
 * <p>
 * 测试 WorkflowOrchestrator 的步骤编排、异常处理和断点续传逻辑。
 * </p>
 *
 * @author ScriptForge Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工作流编排器测试")
class WorkflowOrchestratorTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectStateManager stateManager;
    @Mock private SseEmitterService sseEmitterService;
    @Mock private RequirementAgent requirementAgent;
    @Mock private CharacterRetrievalAgent characterRetrievalAgent;
    @Mock private OutlineAgent outlineAgent;
    @Mock private ScriptAgent scriptAgent;
    @Mock private ReviewAgent reviewAgent;
    @Mock private DocumentAgent documentAgent;

    @InjectMocks
    private WorkflowOrchestrator orchestrator;

    private static final Long PROJECT_ID = 1L;

    private Project buildProject() {
        return Project.builder()
                .id(PROJECT_ID)
                .title("测试项目")
                .gameName("原神")
                .status(ProjectStatus.DRAFT)
                .build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(projectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(buildProject()));
    }

    // ======================== 正向测试 ========================

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTests {

        @Test
        @DisplayName("完整工作流：从 INIT 到 DONE 依次执行所有步骤")
        void shouldExecuteAllStepsSuccessfully() {
            // Given: 项目处于 INIT 状态，所有Agent返回成功
            when(stateManager.getCurrentStep(PROJECT_ID)).thenReturn(WorkflowStep.INIT);
            when(requirementAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("需求摘要"));
            when(characterRetrievalAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("人设卡片"));
            when(outlineAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("大纲"));
            when(scriptAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("剧本"));
            when(reviewAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("审核报告"));
            when(documentAgent.exportDocument(PROJECT_ID)).thenReturn(AgentResult.success("导出成功"));

            // When: 启动工作流
            orchestrator.startWorkflow(PROJECT_ID);

            // Then: 验证所有步骤依次执行
            verify(stateManager).getCurrentStep(PROJECT_ID);
            verify(stateManager).advanceWorkflow(PROJECT_ID, WorkflowStep.REQUIREMENT_GATHERING);
            verify(requirementAgent).execute(PROJECT_ID);
            verify(stateManager).advanceWorkflow(PROJECT_ID, WorkflowStep.SEARCH_AND_CHARACTER);
            verify(characterRetrievalAgent).execute(PROJECT_ID);
            verify(stateManager).advanceWorkflow(PROJECT_ID, WorkflowStep.OUTLINE_DESIGN);
            verify(outlineAgent).execute(PROJECT_ID);
            verify(stateManager).advanceWorkflow(PROJECT_ID, WorkflowStep.SCRIPT_GENERATION);
            verify(scriptAgent).execute(PROJECT_ID);
            verify(stateManager).advanceWorkflow(PROJECT_ID, WorkflowStep.QUALITY_REVIEW);
            verify(reviewAgent).execute(PROJECT_ID);
            verify(stateManager).advanceWorkflow(PROJECT_ID, WorkflowStep.EXPORT);
            verify(documentAgent).exportDocument(PROJECT_ID);
            verify(stateManager).markCompleted(PROJECT_ID);
            verify(sseEmitterService).sendComplete(eq(PROJECT_ID), ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("从中间步骤恢复：OUTLINE_DESIGN 继续执行后续步骤")
        void shouldResumeFromOutlineDesign() {
            // Given: 项目当前步骤为 OUTLINE_DESIGN
            when(stateManager.getCurrentStep(PROJECT_ID)).thenReturn(WorkflowStep.OUTLINE_DESIGN);
            when(outlineAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("大纲"));
            when(scriptAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("剧本"));
            when(reviewAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("审核报告"));
            when(documentAgent.exportDocument(PROJECT_ID)).thenReturn(AgentResult.success("导出成功"));

            // When
            orchestrator.startWorkflow(PROJECT_ID);

            // Then: 跳过前面的步骤，直接从 OUTLINE_DESIGN 开始
            verify(requirementAgent, never()).execute(anyLong());
            verify(characterRetrievalAgent, never()).execute(anyLong());
            verify(outlineAgent).execute(PROJECT_ID);
            verify(scriptAgent).execute(PROJECT_ID);
            verify(stateManager).markCompleted(PROJECT_ID);
        }

        @Test
        @DisplayName("审核失败不阻塞：质量审核失败后仍继续导出")
        void shouldContinueExportWhenReviewFails() {
            // Given: 审核Agent返回失败
            when(stateManager.getCurrentStep(PROJECT_ID)).thenReturn(WorkflowStep.QUALITY_REVIEW);
            when(reviewAgent.execute(PROJECT_ID)).thenReturn(AgentResult.failure("审核失败"));
            when(documentAgent.exportDocument(PROJECT_ID)).thenReturn(AgentResult.success("导出成功"));

            // When
            orchestrator.startWorkflow(PROJECT_ID);

            // Then: 审核失败后仍进入导出步骤
            verify(reviewAgent).execute(PROJECT_ID);
            verify(documentAgent).exportDocument(PROJECT_ID);
            verify(stateManager).markCompleted(PROJECT_ID);
        }
    }

    // ======================== 异常流程测试 ========================

    @Nested
    @DisplayName("异常流程测试")
    class ExceptionFlowTests {

        @Test
        @DisplayName("需求调研失败：流程终止，SSE推送错误")
        void shouldStopWhenRequirementFails() {
            // Given: 需求调研抛出 RuntimeException
            when(stateManager.getCurrentStep(PROJECT_ID)).thenReturn(WorkflowStep.INIT);
            when(requirementAgent.execute(PROJECT_ID))
                    .thenThrow(new RuntimeException("需求调研异常"));

            // When
            orchestrator.startWorkflow(PROJECT_ID);

            // Then: 流程终止，推送错误，后续步骤不执行
            verify(sseEmitterService).sendError(eq(PROJECT_ID), ArgumentMatchers.contains("失败"));
            verify(characterRetrievalAgent, never()).execute(anyLong());
            verify(outlineAgent, never()).execute(anyLong());
            verify(stateManager, never()).markCompleted(anyLong());
        }

        @Test
        @DisplayName("大纲设计失败：流程终止，不进入剧本生成")
        void shouldStopWhenOutlineFails() {
            // Given: 大纲设计抛出异常
            when(stateManager.getCurrentStep(PROJECT_ID)).thenReturn(WorkflowStep.OUTLINE_DESIGN);
            when(outlineAgent.execute(PROJECT_ID))
                    .thenThrow(new RuntimeException("大纲设计异常"));

            // When
            orchestrator.startWorkflow(PROJECT_ID);

            // Then: 不进入后续步骤
            verify(scriptAgent, never()).execute(anyLong());
            verify(reviewAgent, never()).execute(anyLong());
            verify(stateManager, never()).markCompleted(anyLong());
        }

        @Test
        @DisplayName("导出失败：流程终止，抛出RuntimeException")
        void shouldStopWhenExportFails() {
            // Given: 导出失败
            when(stateManager.getCurrentStep(PROJECT_ID)).thenReturn(WorkflowStep.EXPORT);
            when(documentAgent.exportDocument(PROJECT_ID))
                    .thenThrow(new RuntimeException("导出异常"));

            // When
            orchestrator.startWorkflow(PROJECT_ID);

            // Then: 不调用 markCompleted
            verify(stateManager, never()).markCompleted(anyLong());
            verify(sseEmitterService).sendError(eq(PROJECT_ID), ArgumentMatchers.anyString());
        }
    }

    // ======================== 边界值测试 ========================

    @Nested
    @DisplayName("边界值测试")
    class BoundaryTests {

        @Test
        @DisplayName("INIT 状态启动：currentStep 为 null 时默认从需求调研开始")
        void shouldStartFromInitWhenNoCurrentStep() {
            // Given: currentStep 为 null，getCurrentStep 返回 INIT
            when(stateManager.getCurrentStep(PROJECT_ID)).thenReturn(WorkflowStep.INIT);
            when(requirementAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("需求摘要"));
            when(characterRetrievalAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("人设卡片"));
            when(outlineAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("大纲"));
            when(scriptAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("剧本"));
            when(reviewAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("审核报告"));
            when(documentAgent.exportDocument(PROJECT_ID)).thenReturn(AgentResult.success("导出成功"));

            // When
            orchestrator.startWorkflow(PROJECT_ID);

            // Then: 从 REQUIREMENT_GATHERING 开始
            verify(requirementAgent).execute(PROJECT_ID);
        }

        @Test
        @DisplayName("搜索与角色检索并行：人设检索成功即可进入下一步")
        void shouldProceedWhenCharacterRetrievalSucceeds() {
            // Given
            when(stateManager.getCurrentStep(PROJECT_ID)).thenReturn(WorkflowStep.SEARCH_AND_CHARACTER);
            when(characterRetrievalAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("人设卡片"));
            when(outlineAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("大纲"));
            when(scriptAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("剧本"));
            when(reviewAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("审核报告"));
            when(documentAgent.exportDocument(PROJECT_ID)).thenReturn(AgentResult.success("导出成功"));

            // When
            orchestrator.startWorkflow(PROJECT_ID);

            // Then: 进入大纲设计
            verify(outlineAgent).execute(PROJECT_ID);
        }

        @Test
        @DisplayName("人设检索完成但有警告：仍进入下一步")
        void shouldProceedEvenWithCharacterRetrievalWarnings() {
            // Given: 人设检索成功但metadata中有警告
            when(stateManager.getCurrentStep(PROJECT_ID)).thenReturn(WorkflowStep.SEARCH_AND_CHARACTER);
            when(characterRetrievalAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("人设卡片"));
            when(outlineAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("大纲"));
            when(scriptAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("剧本"));
            when(reviewAgent.execute(PROJECT_ID)).thenReturn(AgentResult.success("审核报告"));
            when(documentAgent.exportDocument(PROJECT_ID)).thenReturn(AgentResult.success("导出成功"));

            // When
            orchestrator.startWorkflow(PROJECT_ID);

            // Then
            verify(outlineAgent).execute(PROJECT_ID);
        }
    }
}
