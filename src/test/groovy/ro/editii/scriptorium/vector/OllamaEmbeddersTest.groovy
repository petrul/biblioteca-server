package ro.editii.scriptorium.vector

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest

/**
 * Hits the real Ollama server at ollama.host:ollama.port (zmeu.local:11434
 * by default - see VectorConfig) with the Ollama-backed Embedder beans.
 */
@Tag("external")
@SpringBootTest(
        classes = [VectorConfig.class, MilvusService.class],
        properties = [
            "embedder.address=zmeu.local:11434",
            "milvus.address=zmeu.local:20112",
        ])
class OllamaEmbeddersTest {

    @Autowired
    @Qualifier("bgeM3Embedder")
    Embedder bgeM3Embedder

    @Autowired
    @Qualifier("qwen3EmbeddingEmbedder")
    Embedder qwen3Embedder

    @Autowired
    @Qualifier("nomicEmbedder")
    Embedder nomicEmbedder

    private static final String[] SENTENCES = ["hello there", "how are you", "comment allez-vous?", "ce faci, bă?"]

    @Test
    void bgeM3ProducesExpectedDimensionAndIsTheConfiguredMultilingualModel() {
        assert bgeM3Embedder.modelName() == "BGE_M3"
        assert bgeM3Embedder.vectorDimension() == MilvusCollection.DIM_1024
        final vectors = bgeM3Embedder.encode(SENTENCES)
        assert vectors.length == SENTENCES.length
        vectors.each { assert it.length == MilvusCollection.DIM_1024 }
    }

    // A single-text call and a batched call embed the same text through a
    // different-shaped forward pass (padding/batch dimensions differ), so
    // Ollama doesn't return bit-identical vectors for the two - confirmed
    // empirically. Same text should still land effectively the same point in
    // embedding space, so compare by cosine similarity instead of equality.
    private static double cosineSimilarity(float[] a, float[] b) {
        assert a.length == b.length
        double dot = 0, normA = 0, normB = 0
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB))
    }

    @Test
    void qwen3EmbeddingProducesExpectedDimensionAndDistinctVectors() {
        assert qwen3Embedder.modelName() == "QWEN3_EMBEDDING_4B"

        final vectors = qwen3Embedder.encode(SENTENCES)
        assert vectors.length == SENTENCES.length
        vectors.each { assert it.length == 2560 }

        assert vectors[0] != vectors[1]

        final single = qwen3Embedder.encode(SENTENCES[0])
        assert single.length == 2560
        assert cosineSimilarity(single, vectors[0]) > 0.999
    }

    @Test
    void nomicEmbedProducesExpectedDimensionAndDistinctVectors() {
        assert nomicEmbedder.modelName() == "NOMIC_EMBED_TEXT"

        final vectors = nomicEmbedder.encode(SENTENCES)
        assert vectors.length == SENTENCES.length
        vectors.each { assert it.length == 768 }

        assert vectors[0] != vectors[1]

        final single = nomicEmbedder.encode(SENTENCES[0])
        assert single.length == 768
        assert cosineSimilarity(single, vectors[0]) > 0.999
    }

    @Test
    void theTwoEmbeddersProduceDifferentlyShapedVectorsForTheSameText() {
        final text = SENTENCES[0]
        final qwenVector = qwen3Embedder.encode(text)
        final nomicVector = nomicEmbedder.encode(text)

        assert qwenVector.length != nomicVector.length
    }
}
