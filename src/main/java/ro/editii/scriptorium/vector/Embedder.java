package ro.editii.scriptorium.vector;

/**
 * Common contract for anything that turns text into embedding vectors,
 * regardless of backend (a dedicated sentence-transformers server, an
 * Ollama-served model, etc.) - lets VectorTextSearchService/SearchRestController
 * depend on "an embedder" without caring which implementation backs it.
 */
public interface Embedder {

    default float[] encode(String text) {
        final String[] arr = { text };
        float[][] encoded = this.encode(arr);
        assert encoded.length == 1;
        return encoded[0];
    };

    float[][] encode(String[] texts);

    /**
     * Canonical, Milvus-collection-naming-convention-friendly identifier for
     * whichever model this embedder is backed by (e.g. "ALL_MPNET_BASE_V2",
     * "QWEN3_EMBEDDING_4B") - see VectorTextSearchService's compatibility check.
     */
    String modelName();

    /** Dimension required by the Milvus collection paired with this model. */
    int vectorDimension();

    /**
     * Human-readable description of this specific encoder - where it runs and
     * its name/characteristics - meant for use as a Milvus collection
     * description (see MilvusCollection.create) so anyone inspecting Milvus
     * directly (e.g. via attu) can tell which encoder a collection's vectors
     * came from, without having to cross-reference source code.
     */
    default String describe() {
        return modelName();
    }
}
