package com.erchuang.scriptforge.web.controller;

import com.erchuang.scriptforge.infra.ApiResponse;
import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.erchuang.scriptforge.model.entity.CharacterCard;
import com.erchuang.scriptforge.model.entity.KnowledgeEntry;
import com.erchuang.scriptforge.model.enums.EntryType;
import com.erchuang.scriptforge.service.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库管理 REST 接口.
 *
 * @author ScriptForge Team
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 创建知识库条目.
     */
    @PostMapping("/entries")
    public ApiResponse<KnowledgeEntry> createEntry(@Valid @RequestBody CreateEntryRequest request) {
        EntryType type = EntryType.valueOf(request.getEntryType().toUpperCase());
        KnowledgeEntry entry = knowledgeService.createEntry(
                request.getGameName(), type, request.getTitle(),
                request.getContent(), request.getSourceUrl(), request.getTags());
        return ApiResponse.success(entry);
    }

    /**
     * 查询知识库条目.
     */
    @GetMapping("/entries")
    public ApiResponse<List<KnowledgeEntry>> listEntries(
            @RequestParam String gameName,
            @RequestParam String entryType) {
        EntryType type = EntryType.valueOf(entryType.toUpperCase());
        List<KnowledgeEntry> entries = knowledgeService.listEntries(gameName, type);
        return ApiResponse.success(entries);
    }

    /**
     * 删除知识库条目.
     */
    @DeleteMapping("/entries/{id}")
    public ApiResponse<Void> deleteEntry(@PathVariable Long id) {
        knowledgeService.deleteEntry(id);
        return ApiResponse.success();
    }

    /**
     * 创建/更新角色卡片.
     */
    @PostMapping("/characters")
    public ApiResponse<CharacterCard> saveCharacterCard(@RequestBody CharacterCard card) {
        CharacterCard saved = knowledgeService.saveCharacterCard(card);
        return ApiResponse.success(saved);
    }

    /**
     * 获取游戏的所有角色卡片.
     */
    @GetMapping("/characters")
    public ApiResponse<List<CharacterCard>> listCharacters(@RequestParam String gameName) {
        List<CharacterCard> cards = knowledgeService.getCharacterCards(gameName);
        return ApiResponse.success(cards);
    }

    /**
     * 删除角色卡片.
     */
    @DeleteMapping("/characters/{id}")
    public ApiResponse<Void> deleteCharacter(@PathVariable Long id) {
        knowledgeService.deleteCharacterCard(id);
        return ApiResponse.success();
    }

    // ---- Request DTOs ----

    @Data
    public static class CreateEntryRequest {
        @NotBlank
        private String gameName;
        @NotBlank
        private String entryType;
        @NotBlank
        private String title;
        @NotBlank
        private String content;
        private String sourceUrl;
        private String tags;
    }
}
