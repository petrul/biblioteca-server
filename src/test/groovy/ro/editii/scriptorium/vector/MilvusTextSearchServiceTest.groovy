package ro.editii.scriptorium.vector

import io.milvus.response.SearchResultsWrapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ro.editii.scriptorium.search.content.ContentResolver

import static org.mockito.Mockito.*

class MilvusTextSearchServiceTest {

    RecordingMilvusCollection collection
    Embedder embedder
    ContentResolver contentResolver
    VectorSearchAvailability availability

    @BeforeEach
    void setUp() {
        this.collection = new RecordingMilvusCollection()
        this.embedder = mock(Embedder.class)
        this.contentResolver = mock(ContentResolver.class)
        this.availability = mock(VectorSearchAvailability.class)
        when(embedder.modelName()).thenReturn("QWEN3_EMBEDDING_4B")
    }

    @Test
    void unavailableSearchDoesNotCallTheEmbedderOrMilvus() {
        when(availability.isAvailable()).thenReturn(false)
        final service = new MilvusTextSearchService(collection, embedder, contentResolver, availability)

        assert service.search("query").isEmpty()

        // modelName() is consulted once by the constructor's compatibility
        // guard; the unavailable path must not perform actual encoding.
        verify(embedder, never()).encode("query")
        assert collection.searches == 0
    }

    @Test
    void textSearchEmbedsThenDelegatesToMilvus() {
        final vector = [0.25f, 0.5f] as float[]
        final results = mock(SearchResultsWrapper.class)
        when(availability.isAvailable()).thenReturn(true)
        when(embedder.encode("query")).thenReturn(vector)
        when(results.getIDScore(0)).thenReturn([])
        collection.result = results
        final service = new MilvusTextSearchService(collection, embedder, contentResolver, availability)

        assert service.search("query", 4).isEmpty()

        verify(embedder).encode("query")
        assert collection.searches == 1
        assert collection.topK == 4
        assert collection.vectors[0].toList() == vector.toList()
    }

    @Test
    void embedderFailureDegradesWithoutCallingMilvus() {
        when(availability.isAvailable()).thenReturn(true)
        when(embedder.encode("query")).thenThrow(new IllegalStateException("offline"))
        final service = new MilvusTextSearchService(collection, embedder, contentResolver, availability)

        assert service.search("query").isEmpty()

        assert collection.searches == 0
    }

    @Test
    void milvusFailureDegradesToEmptyResults() {
        // A mid-run Milvus outage/restart: the availability flag is still
        // true (its periodic re-check only flips within its next interval),
        // so the search goes through to a dead Milvus - and must degrade to
        // empty results, not surface the raw SDK exception to the caller.
        final vector = [0.25f, 0.5f] as float[]
        when(availability.isAvailable()).thenReturn(true)
        when(embedder.encode("query")).thenReturn(vector)
        collection.failWith = new IllegalStateException("connection refused")
        final service = new MilvusTextSearchService(collection, embedder, contentResolver, availability)

        assert service.search("query", 4).isEmpty()

        verify(embedder).encode("query")
        assert collection.searches == 1
    }

    @Test
    void rejectsAnEmbedderPairedWithTheWrongCollection() {
        when(embedder.modelName()).thenReturn("BGE_M3")

        final ex = shouldFail {
            new MilvusTextSearchService(collection, embedder, contentResolver, availability)
        }

        assert ex instanceof AssertionError
    }

    private static Throwable shouldFail(Closure closure) {
        try {
            closure.call()
        } catch (Throwable e) {
            return e
        }
        throw new AssertionError("expected an exception but none was thrown")
    }

    private static class RecordingMilvusCollection extends MilvusCollection {
        SearchResultsWrapper result
        RuntimeException failWith
        int searches
        int topK
        float[][] vectors

        RecordingMilvusCollection() {
            super(mock(MilvusService.class), "test_qwen3_embedding_4b")
        }

        @Override
        SearchResultsWrapper search(float[][] vectors, int topK) {
            this.searches++
            if (this.failWith != null)
                throw this.failWith
            this.vectors = vectors
            this.topK = topK
            return result
        }
    }
}
