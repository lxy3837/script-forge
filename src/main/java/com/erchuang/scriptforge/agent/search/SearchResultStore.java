package com.erchuang.scriptforge.agent.search;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 搜索结果与人设分析存储 — 内存缓存 + 文件持久化，服务器重启后数据不丢失.
 *
 * @author ScriptForge Team
 */
@Component
public class SearchResultStore {

    private static final Logger log = LoggerFactory.getLogger(SearchResultStore.class);
    private static final Path DATA_DIR = Paths.get("./data/search");

    private final ConcurrentHashMap<Long, String> searchContent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> characterContent = new ConcurrentHashMap<>();

    public SearchResultStore() {
        try {
            Files.createDirectories(DATA_DIR);
        } catch (IOException e) {
            log.warn("Cannot create search data dir: {}", e.getMessage());
        }
    }

    /** 启动时从磁盘恢复缓存. */
    @PostConstruct
    public void loadFromDisk() {
        try {
            if (!Files.exists(DATA_DIR)) return;
            Files.list(DATA_DIR).forEach(f -> {
                String name = f.getFileName().toString();
                try {
                    Long projectId = extractProjectId(name);
                    if (projectId == null) return;
                    String content = Files.readString(f);
                    if (name.contains("_search")) {
                        searchContent.put(projectId, content);
                        log.debug("Loaded search result for project {}", projectId);
                    } else if (name.contains("_character")) {
                        characterContent.put(projectId, content);
                        log.debug("Loaded character result for project {}", projectId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load search cache file {}: {}", name, e.getMessage());
                }
            });
            log.info("SearchResultStore restored {} search + {} character entries from disk",
                    searchContent.size(), characterContent.size());
        } catch (IOException e) {
            log.warn("Failed to restore search cache: {}", e.getMessage());
        }
    }

    public void saveSearchResult(Long projectId, String content) {
        if (projectId == null || content == null) return;
        searchContent.put(projectId, content);
        writeFile(searchFile(projectId), content);
    }

    public void saveCharacterResult(Long projectId, String content) {
        if (projectId == null || content == null) return;
        characterContent.put(projectId, content);
        writeFile(characterFile(projectId), content);
    }

    public String getSearchResult(Long projectId) {
        return searchContent.get(projectId);
    }

    public String getCharacterResult(Long projectId) {
        return characterContent.get(projectId);
    }

    public void clear(Long projectId) {
        searchContent.remove(projectId);
        characterContent.remove(projectId);
        try {
            Files.deleteIfExists(searchFile(projectId));
            Files.deleteIfExists(characterFile(projectId));
        } catch (IOException ignored) {}
    }

    private Path searchFile(Long projectId) {
        return DATA_DIR.resolve(projectId + "_search.txt");
    }

    private Path characterFile(Long projectId) {
        return DATA_DIR.resolve(projectId + "_character.txt");
    }

    private void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            log.warn("Failed to persist search cache to {}: {}", path, e.getMessage());
        }
    }

    private Long extractProjectId(String filename) {
        int idx = filename.indexOf('_');
        if (idx <= 0) return null;
        try {
            return Long.parseLong(filename.substring(0, idx));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
