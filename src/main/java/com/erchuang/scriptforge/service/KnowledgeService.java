package com.erchuang.scriptforge.service;

import com.erchuang.scriptforge.agent.character.VectorSearchService;
import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.erchuang.scriptforge.model.entity.CharacterCard;
import com.erchuang.scriptforge.model.entity.KnowledgeEntry;
import com.erchuang.scriptforge.model.enums.EntryType;
import com.erchuang.scriptforge.repository.CharacterCardRepository;
import com.erchuang.scriptforge.repository.KnowledgeEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识库管理服务——管理游戏知识和角色人设卡片.
 *
 * @author ScriptForge Team
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final CharacterCardRepository characterCardRepository;
    private final VectorSearchService vectorSearchService;

    public KnowledgeService(KnowledgeEntryRepository knowledgeEntryRepository,
                             CharacterCardRepository characterCardRepository,
                             VectorSearchService vectorSearchService) {
        this.knowledgeEntryRepository = knowledgeEntryRepository;
        this.characterCardRepository = characterCardRepository;
        this.vectorSearchService = vectorSearchService;
    }

    /**
     * 创建知识库条目.
     */
    @Transactional
    public KnowledgeEntry createEntry(String gameName, EntryType entryType, String title,
                                       String content, String sourceUrl, String tags) {
        KnowledgeEntry entry = KnowledgeEntry.builder()
                .gameName(gameName)
                .entryType(entryType)
                .title(title)
                .content(content)
                .sourceUrl(sourceUrl)
                .tags(tags)
                .build();
        entry = knowledgeEntryRepository.save(entry);
        log.info("Created knowledge entry: id={}, title={}", entry.getId(), entry.getTitle());
        return entry;
    }

    /**
     * 查询知识库条目.
     */
    public List<KnowledgeEntry> listEntries(String gameName, EntryType entryType) {
        return knowledgeEntryRepository.findByGameNameAndEntryType(gameName, entryType);
    }

    /**
     * 创建/更新人设卡片并索引到向量库.
     */
    @Transactional
    public CharacterCard saveCharacterCard(CharacterCard card) {
        CharacterCard saved = characterCardRepository.save(card);
        try {
            vectorSearchService.indexCharacterCard(saved);
        } catch (Exception e) {
            log.warn("Failed to index character card {}: {}", saved.getId(), e.getMessage());
        }
        return saved;
    }

    /**
     * 获取游戏的所有角色卡片.
     */
    public List<CharacterCard> getCharacterCards(String gameName) {
        return characterCardRepository.findByGameName(gameName);
    }

    /**
     * 删除知识库条目.
     */
    @Transactional
    public void deleteEntry(Long entryId) {
        if (!knowledgeEntryRepository.existsById(entryId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库条目不存在: " + entryId);
        }
        knowledgeEntryRepository.deleteById(entryId);
    }

    /**
     * 删除人设卡片.
     */
    @Transactional
    public void deleteCharacterCard(Long cardId) {
        if (!characterCardRepository.existsById(cardId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "角色卡片不存在: " + cardId);
        }
        characterCardRepository.deleteById(cardId);
        vectorSearchService.removeCharacterCard(cardId);
    }
}
