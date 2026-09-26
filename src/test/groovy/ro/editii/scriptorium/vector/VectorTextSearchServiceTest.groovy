package ro.editii.scriptorium.vector

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ro.editii.scriptorium.search.content.ContentResolver

/**
 * Pure Groovy-fakes unit test (no Spring context, no Mockito): the search
 * service's collaborators are hand-rolled fakes whose behavior is plain
 * Groovy - more readable than Mockito's when()/doThrow() dance in this
 * language, and immune to the Mockito matcher-state pitfalls Groovy's
 * dynamic dispatch invites (see AdminServicePruneOrphanedElemsTest's
 * history for one such fight).
 */
class VectorTextSearchServiceTest {

    /** Fake embedder - records queries, returns (or throws) on demand. */
    private static class FakeEmbedder implements Embedder {
        float[] encoded = [0.25f, 0.5f] as float[]
        RuntimeException throwOnEncode = null
        List<String> encodedQueries = []
        String model = "QWEN3_EMBEDDING_4B"

        String modelName() { this.model }

        int vectorDimension() { 2560 }

        float[] encode(String text) {
            this.encodedQueries << text
            if (this.throwOnEncode != null)
                throw this.throwOnEncode
            return this.encoded
        }

        float[][] encode(String[] texts) { throw new UnsupportedOperationException() }
    }

    /** Fake availability - a boolean the test flips, nothing more. */
    private static class FakeAvailability extends VectorSearchAvailability {
        boolean available = false

        FakeAvailability() { super(null, null) }

        boolean isAvailable() { this.available }
    }

    /** Fake collection - implements VectorCollection at the searchHits seam. */
    private static class RecordingVectorCollection implements VectorCollection {
        List<VectorSearchHit> result = []
        RuntimeException failWith = null
        int searches = 0
        int topK = 0
        float[] lastVector = null

        String getName() { "test_qwen3_embedding_4b" }

        boolean exists() { throw new UnsupportedOperationException() }

        void create(int vectorDimension, String description) { throw new UnsupportedOperationException() }

        int getVectorDimension() { throw new UnsupportedOperationException() }

        Content findBySha256(String sha256) { throw new UnsupportedOperationException() }

        List<VectorSearchHit> searchHits(float[] vector, int topK) {
            this.searches++
            if (this.failWith != null)
                throw this.failWith
            this.lastVector = vector
            this.topK = topK
            return this.result
        }
    }

    FakeEmbedder embedder
    ContentResolver contentResolver
    FakeAvailability availability
    RecordingVectorCollection collection

    @BeforeEach
    void setUp() {
        this.embedder = new FakeEmbedder()
        this.availability = new FakeAvailability()
        this.collection = new RecordingVectorCollection()
        // The real resolver is an HTTP fetch per hit - here it just marks
        // which URLs got resolved, the map literal IS the fake. The value
        // must be a plain String: a GString would blow up at the Java
        // boundary in VectorHit.from(String, float, String).
        final Map<String, String> resolved = [:]
        this.contentResolver = [resolve: { String url -> resolved[url] = url; "content of ${url}".toString() }] as ContentResolver
    }

    @Test
    void unavailableSearchDoesNotCallTheEmbedderOrTheStore() {
        this.availability.available = false
        final service = new VectorTextSearchService(collection, embedder, contentResolver, availability)

        assert service.search("query").isEmpty()

        assert this.embedder.encodedQueries.isEmpty()
        assert this.collection.searches == 0
    }

    @Test
    void textSearchEmbedsThenDelegatesToTheStore() {
        this.availability.available = true
        this.collection.result = [
                new VectorSearchHit("sha-a", "http://x/a", 3.5f),
                new VectorSearchHit("sha-b", "http://x/b", 9.0f),
        ]
        final service = new VectorTextSearchService(collection, embedder, contentResolver, availability)

        final hits = service.search("query", 4)

        assert this.embedder.encodedQueries == ["query"]
        assert this.collection.searches == 1
        assert this.collection.topK == 4
        assert this.collection.lastVector.toList() == [0.25f, 0.5f]
        assert hits.size() == 2
        assert hits[0].url == "http://x/a"
        assert hits[0].score == 3.5f
        assert hits[0].content == "content of http://x/a"
    }

    @Test
    void embedderFailureDegradesWithoutCallingTheStore() {
        this.availability.available = true
        this.embedder.throwOnEncode = new IllegalStateException("offline")
        final service = new VectorTextSearchService(collection, embedder, contentResolver, availability)

        assert service.search("query").isEmpty()

        assert this.collection.searches == 0
    }

    @Test
    void storeFailureDegradesToEmptyResults() {
        // A mid-run store outage/restart: the availability flag is still
        // true (its periodic re-check only flips within its next interval),
        // so the search goes through to a dead store - and must degrade to
        // empty results, not surface the raw store exception to the caller.
        this.availability.available = true
        this.embedder.encoded = [1.0f] as float[]
        this.collection.failWith = new IllegalStateException("connection refused")
        final service = new VectorTextSearchService(collection, embedder, contentResolver, availability)

        assert service.search("query", 4).isEmpty()

        assert this.embedder.encodedQueries == ["query"]
        assert this.collection.searches == 1
    }

    @Test
    void rejectsAnEmbedderPairedWithTheWrongCollection() {
        this.embedder.model = "BGE_M3"

        final ex = shouldFail {
            new VectorTextSearchService(collection, embedder, contentResolver, availability)
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
}
