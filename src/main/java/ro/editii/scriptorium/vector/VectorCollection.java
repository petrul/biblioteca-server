package ro.editii.scriptorium.vector;

import java.util.List;

/**
 * The store-agnostic vector-collection contract - what
 * VectorTextSearchService and VectorSearchAvailability depend on, so the
 * same search/availability code runs unchanged against Milvus
 * (MilvusCollection, the default) or Qdrant (QdrantCollection,
 * vector.store=qdrant). Same role as the nestjs vectorizer's VectorStore
 * interface, which owns the write path - this one is the server's
 * (almost entirely read) path.
 *
 * Collection NAMING is shared with Milvus on purpose (see
 * VectorTextSearchService.validateModelCollectionCompatible): the same
 * "biblioteca_paragraphs_bge_m3"-style name, derived from the active embedder's
 * modelName, is used whichever store backs it - a name identifies "this
 * corpus embedded with this encoder", never "this store".
 */
public interface VectorCollection {

    /** The convention-carrying name (see VectorTextSearchService's model-name compatibility check). */
    String getName();

    boolean exists();

    /**
     * @param description should identify the encoder that produced (or will
     *                    produce) this collection's vectors - Milvus persists
     *                    it as the collection description; Qdrant has no such
     *                    field, so its implementation accepts and drops it.
     */
    void create(int vectorDimension, String description);

    /** The dimension of the FIELD_EMBEDDING/{"embedding"} vector this collection was created with. */
    int getVectorDimension();

    /**
     * The stored vector of one paragraph (identified by its text's sha256),
     * or null if it was never stored - the ann() search path uses this to
     * search around an existing paragraph without re-embedding its text.
     */
    Content findBySha256(String sha256);

    /** Nearest-neighbor search, returning the topK hits in store ranking order. */
    List<VectorSearchHit> searchHits(float[] vector, int topK);
}
