package ro.editii.scriptorium.vector

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
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
 */
@SpringBootTest(
        classes = [VectorConfig.class, MilvusService.class, ro.editii.scriptorium.health.OllamaHealthTracker.class],
        properties = [
            "sts.host=mini.local",
            "sts.port=11200",
            // The collection round-trip below is milvus-specific machinery
            // (MilvusService.getAt), so opt into the milvus beans explicitly
            // - qdrant is the default store now.
            "vector.store=milvus",
            "vectorstore.address=http://srv2.local:20112",
            "embedder.address=http://zmeu.local:11434",
        ])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration-test")
class StsEmbedderTest {

    static final String TEST_COLLECTION = "test_textbase_paras_sts_all_minilm_l6_v2_stsembeddertest"

    @Autowired
    @Qualifier("model_prod_all_MiniLM_L6_v2")
    StsEmbedder allMiniLmEmbedder

    @Autowired
    @Qualifier("model_all_mpnet_base_v2")
    StsEmbedder allMpnetEmbedder

    @Autowired
    MilvusService milvusService

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

    // Exercises MilvusCollection.create(dim, description) with a real
    // encoder's own describe() - the "collection description must contain
    // where the encoder is, its name, characteristics" requirement - and
    // confirms it actually round-trips through Milvus.
    @Test
    void collectionDescriptionCarriesRealEncoderDetails() {
        final MilvusCollection col = this.milvusService.getAt(TEST_COLLECTION)
        if (col.exists()) col.drop()
        try {
            final description = allMiniLmEmbedder.describe()
            assert description.contains("all-MiniLM-L6-v2")
            assert description.contains("mini.local")
            assert description.contains("11200")

            col.create(MilvusCollection.DIM_384, description)

            assert col.getDescription() == description
            final fieldDescriptions = col.getFieldDescriptions()
            assert fieldDescriptions[MilvusCollection.FIELD_SHA_256].contains("deduplicate")
            assert fieldDescriptions[MilvusCollection.FIELD_URL].contains("retrieve")
            assert fieldDescriptions[MilvusCollection.FIELD_EMBEDDING].contains("same encoder and dimension")
        } finally {
            if (col.exists()) col.drop()
        }
    }
}
