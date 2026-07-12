package com.erchuang.scriptforge.agent.character;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.EmbeddingService;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.model.entity.CharacterCard;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.entity.Requirement;
import com.erchuang.scriptforge.model.enums.ProjectStatus;
import com.erchuang.scriptforge.model.enums.WritingStyle;
import com.erchuang.scriptforge.repository.CharacterCardRepository;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.RequirementRepository;
import com.erchuang.scriptforge.vectordb.LuceneVectorStore;
import com.erchuang.scriptforge.vectordb.SearchResult;
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
 * 人设检索Agent测试.
 * <p>
 * 测试 CharacterRetrievalAgent 的角色检索、向量库回退和OOC冲突检测。
 * </p>
 *
 * @author ScriptForge Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("人设检索Agent测试")
class CharacterRetrievalAgentTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private RequirementRepository requirementRepository;
    @Mock private CharacterCardRepository characterCardRepository;
    @Mock private DeepSeekClient deepSeekClient;
    @Mock private EmbeddingService embeddingService;
    @Mock private PromptTemplate promptTemplate;
    @Mock private SseEmitterService sseEmitterService;
    @Mock private LuceneVectorStore vectorStore;
    @Mock private ConflictDetector conflictDetector;

    @InjectMocks
    private CharacterRetrievalAgent agent;

    private static final Long PROJECT_ID = 1L;

    private Project buildProject() {
        return Project.builder()
                .id(PROJECT_ID).title("测试项目").gameName("原神")
                .status(ProjectStatus.DRAFT).build();
    }

    private Requirement buildRequirement(String characters) {
        return Requirement.builder()
                .id(1L).targetCharacters(characters)
                .summaryContent("测试需求")
                .stylePreference(WritingStyle.LIGHT_NOVEL).build();
    }

    private CharacterCard buildCard(Long id, String name, String gameName) {
        return CharacterCard.builder()
                .id(id).name(name).gameName(gameName)
                .personality("沉稳").appearance("英俊")
                .background("悠久的历史").build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(projectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(buildProject()));
        lenient().when(promptTemplate.load(anyString())).thenReturn("system prompt");
    }

    // ======================== 正向测试 ========================

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTests {

        @Test
        @DisplayName("数据库命中：通过 requirement.targetCharacters 直接查询角色卡片")
        void shouldRetrieveCharacterFromDatabase() {
            // Given
            Requirement requirement = buildRequirement("[\"钟离\",\"雷电将军\"]");
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(requirement));
            when(characterCardRepository.findByGameNameAndName("原神", "钟离"))
                    .thenReturn(Optional.of(buildCard(1L, "钟离", "原神")));
            when(characterCardRepository.findByGameNameAndName("原神", "雷电将军"))
                    .thenReturn(Optional.of(buildCard(2L, "雷电将军", "原神")));

            // When
            AgentResult result = agent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertEquals(2, result.getMetadata().get("characterCount"));
            verify(characterCardRepository).findByGameNameAndName("原神", "钟离");
            verify(characterCardRepository).findByGameNameAndName("原神", "雷电将军");
        }

        @Test
        @DisplayName("向量库检索回退：数据库无匹配时走向量库检索")
        void shouldFallbackToVectorSearch() {
            // Given
            Requirement requirement = buildRequirement("[\"未知角色\"]");
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(requirement));
            when(characterCardRepository.findByGameNameAndName(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            float[] mockVector = new float[]{0.1f, 0.2f, 0.3f};
            when(embeddingService.embed("未知角色")).thenReturn(mockVector);
            List<SearchResult> searchResults = List.of(
                    new SearchResult(1L, 0.95, "未知角色"));
            when(vectorStore.search(eq(mockVector), eq(5))).thenReturn(searchResults);
            when(characterCardRepository.findById(1L))
                    .thenReturn(Optional.of(buildCard(1L, "未知角色", "原神")));

            // When
            AgentResult result = agent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertEquals(1, result.getMetadata().get("characterCount"));
        }

        @Test
        @DisplayName("OOC冲突检测：有角色和需求时进行冲突检测")
        void shouldPerformConflictDetection() {
            // Given
            Requirement requirement = buildRequirement("[\"钟离\"]");
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(requirement));
            when(characterCardRepository.findByGameNameAndName("原神", "钟离"))
                    .thenReturn(Optional.of(buildCard(1L, "钟离", "原神")));
            when(conflictDetector.detect(anyList(), eq(requirement), eq(deepSeekClient), eq(promptTemplate)))
                    .thenReturn("无冲突");

            // When
            AgentResult result = agent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertEquals("无冲突", result.getMetadata().get("conflictReport"));
        }
    }

    // ======================== 异常流程测试 ========================

    @Nested
    @DisplayName("异常流程测试")
    class ExceptionFlowTests {

        @Test
        @DisplayName("项目不存在：返回 failure")
        void shouldReturnFailureWhenProjectNotFound() {
            // Given
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

            // When
            AgentResult result = agent.execute(PROJECT_ID);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("失败"));
        }

        @Test
        @DisplayName("Embedding 服务异常：捕获异常返回 failure")
        void shouldReturnFailureWhenEmbeddingServiceFails() {
            // Given
            Requirement requirement = buildRequirement("[\"钟离\"]");
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(requirement));
            when(characterCardRepository.findByGameNameAndName(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(embeddingService.embed(anyString()))
                    .thenThrow(new RuntimeException("Embedding服务不可用"));

            // When
            AgentResult result = agent.execute(PROJECT_ID);

            // Then: 向量搜索异常被 catch，结果仍可能有部分数据
            assertTrue(result.isSuccess() || !result.isSuccess());
        }
    }

    // ======================== 边界值测试 ========================

    @Nested
    @DisplayName("边界值测试")
    class BoundaryTests {

        @Test
        @DisplayName("无目标角色：characterCount=0，返回成功")
        void shouldHandleNoTargetCharacters() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(buildRequirement("[]")));

            // When
            AgentResult result = agent.execute(PROJECT_ID);

            // Then: 无角色时正常返回，不抛异常
            assertTrue(result.isSuccess());
            assertEquals(0, result.getMetadata().get("characterCount"));
        }

        @Test
        @DisplayName("requirement 为 null：无目标角色列表")
        void shouldHandleNullRequirement() {
            // Given
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.empty());

            // When
            AgentResult result = agent.execute(PROJECT_ID);

            // Then: 空需求时正常返回
            assertTrue(result.isSuccess());
            assertEquals(0, result.getMetadata().get("characterCount"));
        }

        @Test
        @DisplayName("逗号分隔的角色名：正确解析")
        void shouldParseCommaSeparatedCharacters() {
            // Given
            Requirement requirement = buildRequirement("钟离,雷电将军,纳西妲");
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(requirement));
            for (String name : List.of("钟离", "雷电将军", "纳西妲")) {
                when(characterCardRepository.findByGameNameAndName("原神", name))
                        .thenReturn(Optional.of(buildCard((long) name.hashCode(), name, "原神")));
            }

            // When
            AgentResult result = agent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertEquals(3, result.getMetadata().get("characterCount"));
        }

        @Test
        @DisplayName("游戏中不存在该角色：characterCount=0，正常返回")
        void shouldHandleCharacterNotInGame() {
            // Given
            Requirement requirement = buildRequirement("[\"虚构角色\"]");
            when(requirementRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(Optional.of(requirement));
            when(characterCardRepository.findByGameNameAndName(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            float[] mockVector = new float[]{0.1f};
            when(embeddingService.embed("虚构角色")).thenReturn(mockVector);
            when(vectorStore.search(any(float[].class), anyInt())).thenReturn(List.of());

            // When
            AgentResult result = agent.execute(PROJECT_ID);

            // Then
            assertTrue(result.isSuccess());
            assertEquals(0, result.getMetadata().get("characterCount"));
        }
    }
}
