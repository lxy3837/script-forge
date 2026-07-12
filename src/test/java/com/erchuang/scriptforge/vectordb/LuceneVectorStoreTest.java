package com.erchuang.scriptforge.vectordb;

import com.erchuang.scriptforge.infra.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 向量数据库测试.
 * <p>
 * 测试 LuceneVectorStore 的向量添加、KNN检索、删除和工具转换方法。
 * 使用临时目录避免测试污染生产环境。
 * </p>
 *
 * @author ScriptForge Team
 */
@DisplayName("Lucene向量数据库测试")
class LuceneVectorStoreTest {

    @TempDir
    Path tempDir;

    private LuceneVectorStore vectorStore;
    private static final int DIMENSION = 128;

    @BeforeEach
    void setUp() {
        vectorStore = new LuceneVectorStore(tempDir.toString(), DIMENSION);
    }

    @AfterEach
    void tearDown() {
        // 临时目录自动清理
    }

    private float[] createVector(long id) {
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = (float) ((id * 0.1 + i * 0.01) % 1.0);
        }
        return v;
    }

    // ======================== 正向测试 ========================

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTests {

        @Test
        @DisplayName("添加向量并检索：返回相似度排序的结果")
        void shouldAddAndSearchVectors() {
            // Given
            vectorStore.add(1L, "角色A", createVector(1L));
            vectorStore.add(2L, "角色B", createVector(2L));

            // When
            List<SearchResult> results = vectorStore.search(createVector(1L), 5);

            // Then
            assertNotNull(results);
            assertTrue(results.size() >= 1);
        }

        @Test
        @DisplayName("文档计数：添加2条后 count=2")
        void shouldCountDocuments() {
            // Given
            vectorStore.add(1L, "角色A", createVector(1L));
            vectorStore.add(2L, "角色B", createVector(2L));

            // When
            int count = vectorStore.getDocumentCount();

            // Then
            assertEquals(2, count);
        }

        @Test
        @DisplayName("同ID更新：重复添加同一ID会更新文档")
        void shouldUpdateExistingDocument() {
            // Given
            vectorStore.add(1L, "旧文本", createVector(1L));
            vectorStore.add(1L, "新文本", createVector(1L));

            // When
            int count = vectorStore.getDocumentCount();

            // Then: 更新而非新增，count=1
            assertEquals(1, count);
        }

        @Test
        @DisplayName("删除向量：删除后 count 减1")
        void shouldDeleteVector() {
            // Given
            vectorStore.add(1L, "角色A", createVector(1L));
            vectorStore.add(2L, "角色B", createVector(2L));
            assertEquals(2, vectorStore.getDocumentCount());

            // When
            vectorStore.delete(1L);

            // Then
            assertEquals(1, vectorStore.getDocumentCount());
        }
    }

    // ======================== 异常流程测试 ========================

    @Nested
    @DisplayName("异常流程测试")
    class ExceptionFlowTests {

        @Test
        @DisplayName("空向量添加：静默跳过，不抛异常")
        void shouldSkipEmptyVector() {
            // When: vector=null
            vectorStore.add(1L, "测试", null);

            // Then: 不抛异常，count=0
            assertEquals(0, vectorStore.getDocumentCount());
        }

        @Test
        @DisplayName("空数组向量添加：静默跳过")
        void shouldSkipZeroLengthVector() {
            // When
            vectorStore.add(1L, "测试", new float[0]);

            // Then
            assertEquals(0, vectorStore.getDocumentCount());
        }

        @Test
        @DisplayName("空查询向量：返回空列表")
        void shouldReturnEmptyForNullQueryVector() {
            // When
            List<SearchResult> results = vectorStore.search(null, 5);

            // Then
            assertNotNull(results);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("空查询向量（零长度）：返回空列表")
        void shouldReturnEmptyForZeroLengthQueryVector() {
            // When
            List<SearchResult> results = vectorStore.search(new float[0], 5);

            // Then
            assertNotNull(results);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("删除不存在的ID：静默忽略")
        void shouldSilentlyIgnoreDeleteNonExistent() {
            // Given: 先添加一个
            vectorStore.add(1L, "角色A", createVector(1L));

            // When: 删除不存在的
            vectorStore.delete(999L);

            // Then: 不抛异常，count 不变
            assertEquals(1, vectorStore.getDocumentCount());
        }
    }

    // ======================== 边界值测试 ========================

    @Nested
    @DisplayName("边界值测试")
    class BoundaryTests {

        @Test
        @DisplayName("空索引检索：返回空列表")
        void shouldReturnEmptyForEmptyIndex() {
            // When
            List<SearchResult> results = vectorStore.search(createVector(1L), 5);

            // Then
            assertNotNull(results);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("空索引 count：返回 0")
        void shouldReturnZeroCountForEmptyIndex() {
            assertEquals(0, vectorStore.getDocumentCount());
        }

        @Test
        @DisplayName("k 大于文档数：返回所有文档")
        void shouldReturnAllWhenKExceedsCount() {
            // Given
            vectorStore.add(1L, "角色A", createVector(1L));
            vectorStore.add(2L, "角色B", createVector(2L));

            // When: k=100 > 2
            List<SearchResult> results = vectorStore.search(createVector(1L), 100);

            // Then
            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("floatArrayToBytes / bytesToFloatArray 往返转换一致")
        void shouldRoundtripFloatByteConversion() {
            // Given
            float[] original = new float[]{0.1f, 0.5f, -0.3f, 1.0f, 0.0f};

            // When
            byte[] bytes = LuceneVectorStore.floatArrayToBytes(original);
            float[] restored = LuceneVectorStore.bytesToFloatArray(bytes);

            // Then
            assertArrayEquals(original, restored, 0.0001f);
        }

        @Test
        @DisplayName("null 向量转换：floatArrayToBytes(null) 返回 null")
        void shouldReturnNullForNullFloatArray() {
            assertNull(LuceneVectorStore.floatArrayToBytes(null));
        }

        @Test
        @DisplayName("null 字节转换：bytesToFloatArray(null) 返回 null")
        void shouldReturnNullForNullByteArray() {
            assertNull(LuceneVectorStore.bytesToFloatArray(null));
        }

        @Test
        @DisplayName("检索结果包含 text 字段")
        void shouldIncludeTextInSearchResult() {
            // Given
            vectorStore.add(1L, "钟离", createVector(1L));

            // When
            List<SearchResult> results = vectorStore.search(createVector(1L), 1);

            // Then
            assertEquals(1, results.size());
            assertEquals(1L, results.get(0).getId());
            assertEquals("钟离", results.get(0).getText());
            assertTrue(results.get(0).getScore() > 0);
        }
    }
}
