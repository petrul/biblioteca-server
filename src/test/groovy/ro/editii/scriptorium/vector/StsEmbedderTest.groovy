package ro.editii.scriptorium.vector

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest

/**
 * Hits the real sentence-transformers server at sts.host:sts.port
 * (mini.local:11200 by default - see VectorConfig) with the two STS-backed
 * Embedder beans: model_prod_all_MiniLM_L6_v2 (@Primary - the production
 * default for now) and model_all_mpnet_base_v2.
 *
 * Unlike OllamaEmbeddersTest, this is NOT @Disabled: the sentence-transformers
 * server does small-model CPU inference, not GPU-contended LLM work, so it's
 * fast/reliable enough to run every time - this is deliberately the
 * "make encoding test target sts" test.
 *
 * Migrated off the retired milvus: the three encoding tests never needed a
 * store, and the old fourth test's collection round-trip (milvus persisted
 * the embedder description as collection metadata) has no qdrant equivalent -
 * VectorCollection.create() documents qdrant accepting-and-dropping the
 * description - so what survives is the describe() contract itself, the
 * part the search layer still reads. No store beans at all: vector.store=none
 * keeps the context free of both the milvus and the qdrant wiring.
 */
@SpringBootTest(
        classes = [VectorConfig.class, ro.editii.scriptorium.health.OllamaHealthTracker.class],
        properties = [
            "sts.host=mini.local",
            "sts.port=11200",
            // Neither store is needed here: the conditional collection beans
            // (milvus / qdrant-by-default) are both opted out of.
            "vector.store=none",
            "embedder.address=http://zmeu.local:11434",
        ])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration-test")
class StsEmbedderTest {

    @Autowired
    @Qualifier("model_prod_all_MiniLM_L6_v2")
    StsEmbedder allMiniLmEmbedder

    @Autowired
    @Qualifier("model_all_mpnet_base_v2")
    StsEmbedder allMpnetEmbedder

    private static final String[] SENTENCES = ["hello there", "how are you", "comment allez-vous?", "ce faci, bă?"]

    // A single-text call and a batched call embed the same text through a
    // different-shaped forward pass (padding/batch dimensions differ), so
    // an encoder isn't guaranteed bit-identical vectors for the two -
    // compare by cosine similarity instead of equality, same reasoning as
    // OllamaEmbeddersTest.
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
    void allMiniLmProducesExpectedDimensionAndDistinctVectors() {
        assert allMiniLmEmbedder.modelName() == "all-MiniLM-L6-v2"

        final vectors = allMiniLmEmbedder.encode(SENTENCES)
        assert vectors.length == SENTENCES.length
        vectors.each { assert it.length == MilvusCollection.DIM_384 }

        assert vectors[0] != vectors[1]

        final single = allMiniLmEmbedder.encode(SENTENCES[0])
        assert single.length == MilvusCollection.DIM_384
        assert cosineSimilarity(single, vectors[0]) > 0.999
    }

    @Test
    void allMpnetProducesExpectedDimensionAndDistinctVectors() {
        assert allMpnetEmbedder.modelName() == "all-mpnet-base-v2"

        final vectors = allMpnetEmbedder.encode(SENTENCES)
        assert vectors.length == SENTENCES.length
        vectors.each { assert it.length == MilvusCollection.DIM_768 }

        assert vectors[0] != vectors[1]
    }

    @Test
    void theTwoEncodersProduceDifferentlyShapedVectorsForTheSameText() {
        final text = SENTENCES[0]
        final miniLmVector = allMiniLmEmbedder.encode(text)
        final mpnetVector = allMpnetEmbedder.encode(text)

        assert miniLmVector.length != mpnetVector.length
    }

    /**
     * The describe() contract that used to feed MilvusCollection.create()'s
     * description (milvus persisted it as collection metadata; qdrant has no
     * such field, so VectorCollection.create() accepts-and-drops it - see its
     * own javadoc). The part the system still depends on is what describe()
     * itself must carry: where the encoder runs, its name, characteristics.
     */
    @Test
    void collectionDescriptionCarriesRealEncoderDetails() {
        final description = allMiniLmEmbedder.describe()
        assert description.contains("all-MiniLM-L6-v2")
        assert description.contains("mini.local")
        assert description.contains("11200")
    }
}
