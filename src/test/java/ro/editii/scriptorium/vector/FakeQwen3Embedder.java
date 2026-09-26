package ro.editii.scriptorium.vector;

import java.util.Random;

/**
 * Deterministic, network-free stand-in for the real Ollama-backed embedder.
 * For tests that need *an* Embedder bean (to satisfy DI, or the
 * collection-name/model-name compatibility check in
 * VectorTextSearchService) but aren't testing embedding quality
 * themselves - that's OllamaEmbeddersTest's job, against the real Ollama
 * server. Vectors are seeded from the input text's hashCode, so the same
 * text always produces the same vector within a run, and different texts
 * produce different vectors (both properties some tests assert on).
 */
public class FakeQwen3Embedder implements Embedder {

    public static final int DIM = MilvusCollection.DIM_2560;

    // matches the real qwen3EmbeddingEmbedder's modelName() - tests rely on
    // this being part of the Milvus collection name they use (see
    // VectorTextSearchService.validateModelCollectionCompatible).
    public static final String MODEL_NAME = "QWEN3_EMBEDDING_4B";

    @Override
    public float[] encode(String text) {
        final Random rnd = new Random(text.hashCode());
        final float[] vector = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            vector[i] = rnd.nextFloat();
        }
        return vector;
    }

    @Override
    public float[][] encode(String[] texts) {
        final float[][] result = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            result[i] = encode(texts[i]);
        }
        return result;
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public int vectorDimension() {
        return DIM;
    }
}
