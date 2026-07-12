package com.erchuang.scriptforge.agent.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 搜索缓存管理器 — 网络不可用时回退使用缓存的搜索结果.
 *
 * 缓存键 = gameName + 需求特征哈希后缀，避免同一游戏不同需求场景的缓存冲突。
 * 支持按 TTL 自动过期和强制失效。
 *
 * @author ScriptForge Team
 */
@Component
public class CacheManager {

    private static final Logger log = LoggerFactory.getLogger(CacheManager.class);

    /** 缓存：cacheKey -> 搜索结果 */
    private final Map<String, String> searchCache = new ConcurrentHashMap<>();

    /** 缓存时间戳：cacheKey -> 写入时间 */
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();

    /** 默认缓存有效期（毫秒）：24小时 */
    private static final long DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L;

    // ==================== 公开 API ====================

    /**
     * 缓存搜索结果（使用 gameName + requirementContext 生成键，防止冲突）.
     *
     * @param gameName           游戏名称
     * @param requirementContext 需求上下文（用于区分不同场景）
     * @param result             搜索结果
     */
    public void cacheResult(String gameName, String requirementContext, String result) {
        if (gameName == null || result == null) return;
        String key = buildCacheKey(gameName, requirementContext);
        searchCache.put(key, result);
        cacheTimestamps.put(key, System.currentTimeMillis());
        log.debug("Cached search result for key: {}", key);
    }

    /**
     * 缓存搜索结果（向后兼容，使用默认TTL，不含需求上下文）.
     *
     * @param gameName 游戏名称
     * @param result   搜索结果
     */
    public void cacheResult(String gameName, String result) {
        cacheResult(gameName, "", result);
    }

    /**
     * 获取缓存的搜索结果.
     *
     * @param gameName           游戏名称
     * @param requirementContext 需求上下文
     * @return 搜索结果，不存在或过期返回 null
     */
    public String getCachedResult(String gameName, String requirementContext) {
        if (gameName == null) return null;
        String key = buildCacheKey(gameName, requirementContext);
        return doGetCached(key, DEFAULT_TTL_MS);
    }

    /**
     * 获取缓存的搜索结果（指定TTL）.
     *
     * @param gameName           游戏名称
     * @param requirementContext 需求上下文
     * @param ttlMs              有效期（毫秒）
     * @return 搜索结果，不存在或过期返回 null
     */
    public String getCachedResult(String gameName, String requirementContext, long ttlMs) {
        if (gameName == null) return null;
        String key = buildCacheKey(gameName, requirementContext);
        return doGetCached(key, ttlMs);
    }

    /**
     * 获取缓存的搜索结果（向后兼容，不含需求上下文）.
     *
     * @param gameName 游戏名称
     * @return 搜索结果
     */
    public String getCachedResult(String gameName) {
        return getCachedResult(gameName, "", DEFAULT_TTL_MS);
    }

    /**
     * 使指定游戏的所有缓存失效.
     */
    public void invalidate(String gameName) {
        if (gameName == null) return;
        String prefix = gameName + "::";
        searchCache.keySet().removeIf(k -> k.startsWith(prefix));
        cacheTimestamps.keySet().removeIf(k -> k.startsWith(prefix));
        log.debug("Invalidated all caches for game: {}", gameName);
    }

    /**
     * 清除所有缓存.
     */
    public void clearAll() {
        searchCache.clear();
        cacheTimestamps.clear();
        log.info("All search caches cleared");
    }

    // ==================== 内部方法 ====================

    private String doGetCached(String key, long ttlMs) {
        Long timestamp = cacheTimestamps.get(key);
        if (timestamp != null && System.currentTimeMillis() - timestamp < ttlMs) {
            String result = searchCache.get(key);
            if (result != null) {
                log.debug("Cache hit for key: {}", key);
                return result;
            }
        }
        // 过期清理
        if (timestamp != null) {
            searchCache.remove(key);
            cacheTimestamps.remove(key);
        }
        return null;
    }

    /**
     * 构建缓存键：gameName + 需求特征哈希（前8位）.
     * 同一游戏的不同需求场景生成不同的缓存键，避免冲突.
     */
    private String buildCacheKey(String gameName, String requirementContext) {
        if (requirementContext == null || requirementContext.isBlank()) {
            return gameName;
        }
        // 提取需求中的关键特征作为哈希输入
        // 取前200字进行哈希，忽略格式细节
        String feature = requirementContext.length() > 200
                ? requirementContext.substring(0, 200)
                : requirementContext;
        String hash = String.format("%08x", feature.hashCode());
        return gameName + "::" + hash;
    }
}
