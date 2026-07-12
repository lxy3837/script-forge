package com.erchuang.scriptforge.agent.character;

import com.erchuang.scriptforge.llm.DeepSeekClient;
import com.erchuang.scriptforge.llm.DeepSeekClient.ChatMessage;
import com.erchuang.scriptforge.llm.EmbeddingService;
import com.erchuang.scriptforge.llm.PromptTemplate;
import com.erchuang.scriptforge.model.entity.CharacterCard;
import com.erchuang.scriptforge.vectordb.LuceneVectorStore;
import com.erchuang.scriptforge.vectordb.SearchResult;
import com.erchuang.scriptforge.vectordb.VectorDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 向量相似度检索服务——封装Lucene KNN检索的调用逻辑.
 *
 * @author ScriptForge Team
 */
@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    private final LuceneVectorStore vectorStore;
    private final EmbeddingService embeddingService;

    public VectorSearchService(LuceneVectorStore vectorStore, EmbeddingService embeddingService) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
    }

    /**
     * 基于文本查询检索相似的角色卡片.
     *
     * @param queryText 查询文本（角色名称或描述）
     * @param topK      返回结果数
     * @return 检索结果列表
     */
    public List<SearchResult> searchSimilar(String queryText, int topK) {
        float[] queryVector = embeddingService.embed(queryText);
        return vectorStore.search(queryVector, topK);
    }

    /**
     * 批量检索多个文本的相似结果.
     *
     * @param texts 查询文本列表
     * @param topK  每个查询的结果数
     * @return 检索结果列表
     */
    public List<SearchResult> searchSimilarBatch(List<String> texts, int topK) {
        List<float[]> vectors = List.of(embeddingService.embedBatch(texts));
        List<SearchResult> allResults = new java.util.ArrayList<>();

        for (float[] vector : vectors) {
            allResults.addAll(vectorStore.search(vector, topK));
        }

        // 去重按id
        return allResults.stream()
                .collect(Collectors.toMap(SearchResult::getId, sr -> sr, (a, b) -> a))
                .values().stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());
    }

    /**
     * 索引人物卡片到向量库.
     *
     * @param card 人物卡片
     */
    public void indexCharacterCard(CharacterCard card) {
        if (card.getEmbedding() == null) {
            // 需要先生成embedding
            String searchText = card.getName() + " " + card.getGameName();
            float[] vector = embeddingService.embed(searchText);
            card.setEmbeddingFloats(vector);
        }

        float[] vector = card.getEmbeddingFloats();
        if (vector != null) {
            vectorStore.add(card.getId(), card.getName(), vector);
            log.debug("Indexed character card: {} (id={})", card.getName(), card.getId());
        }
    }

    /**
     * 从向量库移除人物卡片.
     *
     * @param cardId 卡片ID
     */
    public void removeCharacterCard(long cardId) {
        vectorStore.delete(cardId);
        log.debug("Removed character card from vector store: id={}", cardId);
    }
}
