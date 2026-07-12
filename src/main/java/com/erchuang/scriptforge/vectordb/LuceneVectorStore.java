package com.erchuang.scriptforge.vectordb;

import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.erchuang.scriptforge.infra.FileUtils;
import org.apache.lucene.codecs.lucene99.Lucene99Codec;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于Lucene KNN的向量索引管理器.
 * <p>
 * 使用Lucene 9.x的KnnVectorField实现向量相似度检索。
 * 支持add/search/delete操作，底层索引持久化到磁盘。
 * </p>
 *
 * @author ScriptForge Team
 */
@Component
public class LuceneVectorStore {

    private static final Logger log = LoggerFactory.getLogger(LuceneVectorStore.class);

    /** Lucene文档字段名：记录ID */
    private static final String FIELD_ID = "id";
    /** Lucene文档字段名：原始文本 */
    private static final String FIELD_TEXT = "text";
    /** Lucene文档字段名：向量 */
    private static final String FIELD_VECTOR = "vector";

    private final String indexDir;
    private final int dimension;

    public LuceneVectorStore(
            @Value("${vector-db.index-dir}") String indexDir,
            @Value("${vector-db.dimension}") int dimension) {
        this.indexDir = indexDir;
        this.dimension = dimension;
        initIndex();
    }

    /**
     * 初始化索引目录，确保索引存在.
     */
    private void initIndex() {
        FileUtils.ensureDirectoryExists(indexDir);
        log.info("Lucene Vector Store initialized at {}, dimension={}", indexDir, dimension);
    }

    /**
     * 添加向量到索引.
     *
     * @param id     关联记录ID
     * @param text   原始文本
     * @param vector 向量数据
     */
    public void add(long id, String text, float[] vector) {
        if (vector == null || vector.length == 0) {
            log.warn("Skip adding empty vector for id={}", id);
            return;
        }

        try (FSDirectory dir = FSDirectory.open(Path.of(indexDir));
             IndexWriter writer = createWriter(dir)) {

            Document doc = new Document();
            doc.add(new LongPoint(FIELD_ID, id));
            doc.add(new StoredField(FIELD_ID, id));
            doc.add(new TextField(FIELD_TEXT, text != null ? text : "", Field.Store.YES));
            doc.add(new KnnFloatVectorField(FIELD_VECTOR, vector));

            writer.updateDocument(new Term(FIELD_ID, String.valueOf(id)), doc);
            writer.commit();

            log.debug("Added vector for id={}, text='{}'", id,
                    text != null && text.length() > 30 ? text.substring(0, 30) + "..." : text);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Failed to add vector to Lucene index: " + e.getMessage(), e);
        }
    }

    /**
     * KNN向量相似度检索——返回最相似的k条结果.
     *
     * @param queryVector 查询向量
     * @param k           返回结果数
     * @return 检索结果列表（按相似度降序）
     */
    public List<SearchResult> search(float[] queryVector, int k) {
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }

        try (FSDirectory dir = FSDirectory.open(Path.of(indexDir));
             DirectoryReader reader = DirectoryReader.open(dir)) {

            IndexSearcher searcher = new IndexSearcher(reader);

            // KNN查询
            Query knnQuery = new KnnFloatVectorQuery(FIELD_VECTOR, queryVector, k);
            TopDocs topDocs = searcher.search(knnQuery, k);

            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                long id = doc.getField(FIELD_ID).numericValue().longValue();
                String text = doc.get(FIELD_TEXT);
                double score = scoreDoc.score;
                results.add(new SearchResult(id, score, text));
            }

            log.debug("Vector search returned {} results for k={}", results.size(), k);
            return results;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Failed to search Lucene index: " + e.getMessage(), e);
        }
    }

    /**
     * 删除指定ID的向量.
     *
     * @param id 记录ID
     */
    public void delete(long id) {
        try (FSDirectory dir = FSDirectory.open(Path.of(indexDir));
             IndexWriter writer = createWriter(dir)) {

            writer.deleteDocuments(new Term(FIELD_ID, String.valueOf(id)));
            writer.commit();

            log.debug("Deleted vector for id={}", id);
        } catch (IOException e) {
            log.warn("Failed to delete vector for id={}: {}", id, e.getMessage());
        }
    }

    /**
     * 获取当前索引中的文档数量.
     *
     * @return 文档数
     */
    public int getDocumentCount() {
        try (FSDirectory dir = FSDirectory.open(Path.of(indexDir));
             DirectoryReader reader = DirectoryReader.open(dir)) {
            return reader.numDocs();
        } catch (IOException e) {
            log.warn("Failed to get document count: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 创建IndexWriter实例.
     */
    private IndexWriter createWriter(FSDirectory dir) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig();
        config.setCodec(new Lucene99Codec());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        return new IndexWriter(dir, config);
    }

    /**
     * 将float[]转为byte[].
     */
    public static byte[] floatArrayToBytes(float[] floats) {
        if (floats == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * Float.BYTES);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /**
     * 将byte[]转为float[].
     */
    public static float[] bytesToFloatArray(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] floats = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }
}
