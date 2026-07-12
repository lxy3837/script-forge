package com.erchuang.scriptforge.agent.character;

import com.erchuang.scriptforge.agent.orchestrator.AgentResult;
import com.erchuang.scriptforge.infra.SseEmitterService;
import com.erchuang.scriptforge.infra.WorkspaceFileWriter;
import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.DeepSeekClient.ChatMessage;
import com.erchuang.scriptforge.llm.EmbeddingService;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.model.dto.SseEventDTO;
import com.erchuang.scriptforge.model.entity.CharacterCard;
import com.erchuang.scriptforge.model.entity.Project;
import com.erchuang.scriptforge.model.entity.Requirement;
import com.erchuang.scriptforge.repository.CharacterCardRepository;
import com.erchuang.scriptforge.repository.ProjectRepository;
import com.erchuang.scriptforge.repository.RequirementRepository;
import com.erchuang.scriptforge.stream.StreamTracker;
import com.erchuang.scriptforge.vectordb.LuceneVectorStore;
import com.erchuang.scriptforge.vectordb.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 人设检索Agent——批量查询角色人设，生成人设卡片，检测OOC冲突风险.
 *
 * @author ScriptForge Team
 */
@Component
public class CharacterRetrievalAgent {

    private static final Logger log = LoggerFactory.getLogger(CharacterRetrievalAgent.class);

    private final ProjectRepository projectRepository;
    private final RequirementRepository requirementRepository;
    private final CharacterCardRepository characterCardRepository;
    private final DeepSeekClient deepSeekClient;
    private final EmbeddingService embeddingService;
    private final PromptTemplate promptTemplate;
    private final SseEmitterService sseEmitterService;
    private final LuceneVectorStore vectorStore;
    private final ConflictDetector conflictDetector;
    private final WorkspaceFileWriter workspaceFileWriter;

    public CharacterRetrievalAgent(ProjectRepository projectRepository,
                                    RequirementRepository requirementRepository,
                                    CharacterCardRepository characterCardRepository,
                                    DeepSeekClient deepSeekClient,
                                    EmbeddingService embeddingService,
                                    PromptTemplate promptTemplate,
                                    SseEmitterService sseEmitterService,
                                    LuceneVectorStore vectorStore,
                                    ConflictDetector conflictDetector,
                                    WorkspaceFileWriter workspaceFileWriter) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.characterCardRepository = characterCardRepository;
        this.deepSeekClient = deepSeekClient;
        this.embeddingService = embeddingService;
        this.promptTemplate = promptTemplate;
        this.sseEmitterService = sseEmitterService;
        this.vectorStore = vectorStore;
        this.conflictDetector = conflictDetector;
        this.workspaceFileWriter = workspaceFileWriter;
    }

    /**
     * 执行人设检索.
     *
     * @param projectId 项目ID
     * @return Agent执行结果
     */
    public AgentResult execute(Long projectId) {
        log.info("CharacterRetrievalAgent started for project {}", projectId);

        try {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

            // 获取需求中的目标角色
            Requirement requirement = requirementRepository.findByProjectId(projectId).orElse(null);
            List<String> targetCharacters = parseTargetCharacters(requirement);

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("CHARACTER", "正在检索角色人设...", 10));

            // 从数据库查找角色卡片
            List<CharacterCard> characterCards = new ArrayList<>();
            for (String characterName : targetCharacters) {
                CharacterCard card = characterCardRepository
                        .findByGameNameAndName(project.getGameName(), characterName)
                        .orElse(null);
                if (card != null) {
                    characterCards.add(card);
                }
            }

            // 如果数据库中没有，尝试从向量库检索
            if (characterCards.isEmpty() && !targetCharacters.isEmpty()) {
                characterCards = searchFromVectorStore(project.getGameName(), targetCharacters);
            }

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("CHARACTER", "正在构建人设卡片...", 40));

            // 构建人设卡片
            StreamTracker.startStep(projectId, "character", "人设分析");
            CharacterCardBuilder cardBuilder = new CharacterCardBuilder(deepSeekClient, promptTemplate);
            String characterCardsText = cardBuilder.buildCards(characterCards, project.getGameName());
            StreamTracker.updateStep(projectId, "character", characterCardsText, 50);

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.running("CHARACTER", "正在检测OOC冲突风险...", 70));

            // 冲突检测——流式推送到前端
            String conflictReport = "";
            if (!characterCards.isEmpty() && requirement != null) {
                StreamTracker.updateStep(projectId, "character",
                        "\n\n### OOC 冲突风险检测\n\n", -1);
                conflictReport = conflictDetector.detectAndStream(projectId, characterCards, requirement,
                        deepSeekClient, promptTemplate);
            }

            StreamTracker.endStep(projectId, "character", "completed", 100);

            log.info("CharacterRetrievalAgent completed for project {}, found {} characters",
                    projectId, characterCards.size());

            sseEmitterService.sendProgress(projectId,
                    SseEventDTO.completed("CHARACTER", "人设检索完成"));

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("characterCount", characterCards.size());
            metadata.put("conflictReport", conflictReport);

            // 写入工作空间文件
            if (characterCardsText != null && !characterCardsText.isBlank()) {
                workspaceFileWriter.write(projectId, "人设检索.md", characterCardsText);
            }

            return AgentResult.success(characterCardsText, metadata);
        } catch (Exception e) {
            log.error("CharacterRetrievalAgent failed: {}", e.getMessage(), e);
            return AgentResult.failure("人设检索失败: " + e.getMessage());
        }
    }

    /**
     * 解析需求中的目标角色列表.
     */
    private List<String> parseTargetCharacters(Requirement requirement) {
        if (requirement == null || requirement.getTargetCharacters() == null) {
            return List.of();
        }
        try {
            String chars = requirement.getTargetCharacters();
            // 尝试解析JSON数组
            if (chars.startsWith("[")) {
                return List.of(chars.replaceAll("[\\[\\]\"]", "").split(",")).stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            }
            // 逗号分隔
            return Arrays.stream(chars.split("[,，]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 从向量库检索角色信息.
     */
    private List<CharacterCard> searchFromVectorStore(String gameName, List<String> characterNames) {
        List<CharacterCard> results = new ArrayList<>();
        for (String name : characterNames) {
            try {
                float[] queryVector = embeddingService.embed(name);
                List<SearchResult> searchResults = vectorStore.search(queryVector, 5);

                for (SearchResult sr : searchResults) {
                    CharacterCard card = characterCardRepository.findById(sr.getId()).orElse(null);
                    if (card != null && !results.contains(card)) {
                        results.add(card);
                    }
                }
            } catch (Exception e) {
                log.warn("Vector search failed for character: {}", name, e);
            }
        }
        return results;
    }
}
