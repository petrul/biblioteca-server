package ro.editii.scriptorium.vector;

/**
 * Neutral search result of whichever {@link VectorCollection} store is
 * active - the store-agnostic counterpart of Milvus's
 * SearchResultsWrapper.IDScore, carrying exactly the fields
 * VectorUtils.searchHitsToHits resolves a {@link ro.editii.scriptorium.search.VectorHit}
 * from: the paragraph's content hash, its canonical Textbase URL, and the
 * store's own score (L2 distance for both stores as configured - lower is
 * closer, so ranking behaves identically across Milvus and Qdrant).
 */
public record VectorSearchHit(String sha256, String url, float score) { }
