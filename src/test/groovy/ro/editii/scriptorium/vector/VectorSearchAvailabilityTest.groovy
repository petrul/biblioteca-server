package ro.editii.scriptorium.vector

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pure Groovy-fakes unit test (no Spring context, no Mockito - the same
 * hand-rolled-fake harness as VectorTextSearchServiceTest) for
 * VectorSearchAvailability's periodic recheckAvailability(): the
 * counterpart of the one-shot startup check that flips the availability
 * flag within one interval of the vector store going away and coming
 * back, so neither direction needs a server restart anymore (a store
 * outage at boot used to keep search silently off for the whole run).
 *
 * The availability check is VectorCollection-based (store-agnostic since
 * the qdrant store landed - MilvusCollection and QdrantCollection both
 * implement it), so a mutable Groovy fake stands in for the store: each
 * phase of the test just flips fields on the fake, no re-stubbing.
 */
class VectorSearchAvailabilityTest {

    /** Mutable fake - the test flips fields between rechecks. */
    private static class FakeVectorCollection implements VectorCollection {
        String name = "test_bge_m3"
        boolean present = true
        RuntimeException throwOnExists = null
        int reportedDimension = 1024
        int existsCalls = 0

        String getName() { this.name }

        boolean exists() {
            this.existsCalls++
            if (this.throwOnExists != null)
                throw this.throwOnExists
            return this.present
        }

        void create(int vectorDimension, String description) { throw new UnsupportedOperationException() }

        int getVectorDimension() { return this.reportedDimension }

        Content findBySha256(String sha256) { throw new UnsupportedOperationException() }

        List<VectorSearchHit> searchHits(float[] vector, int topK) { throw new UnsupportedOperationException() }
    }

    private static class FakeEmbedder implements Embedder {
        String modelName() { "BGE_M3" }
        int vectorDimension() { 1024 }
        float[][] encode(String[] texts) { throw new UnsupportedOperationException() }
    }

    FakeVectorCollection collection
    VectorSearchAvailability availability

    @BeforeEach
    void setUp() {
        this.collection = new FakeVectorCollection()
        this.availability = new VectorSearchAvailability(this.collection, new FakeEmbedder())
        this.availability.availabilityCheckEnabled = true
        // Direct construction leaves @Value fields at their Java defaults -
        // the recheck tests below exercise recheckAvailability() itself, so
        // opt into the periodic behavior explicitly (build.gradle turns the
        // property off for the store-integration tests, not for this one).
        this.availability.recheckEnabled = true
    }

    @Test
    void recheckFlipsOffWhenTheStoreIsGoneAndBackWhenItReturns() {
        // As left by a successful startup check.
        this.availability.available = true

        // Store gone: the check itself blows up (connection refused), not
        // just returns false - the recheck must swallow that and flip off.
        this.collection.throwOnExists = new IllegalStateException("connection refused")
        this.availability.recheckAvailability()
        assert !this.availability.isAvailable()

        // Still gone: no change (and no per-cycle log - transitions only).
        this.availability.recheckAvailability()
        assert !this.availability.isAvailable()

        // Store back: collection present and the vector dimension agrees
        // with the embedder's - the recheck flips search on again.
        this.collection.throwOnExists = null
        this.availability.recheckAvailability()
        assert this.availability.isAvailable()
    }

    @Test
    void recheckStaysOffWhenTheDimensionDisagreesWithTheEmbedder() {
        // The collection is reachable but was built for another encoder:
        // searching it with this run's embedder would mix vector spaces.
        this.availability.available = true
        this.collection.reportedDimension = 384
        this.availability.recheckAvailability()
        assert !this.availability.isAvailable()
    }

    @Test
    void recheckLeavesSearchOffWhenTheCheckIsDisabled() {
        this.availability.available = false

        // The store is perfectly reachable, but the check is disabled - the
        // recheck must not resurrect search behind the configured flag's
        // back, nor touch the store at all.
        this.availability.availabilityCheckEnabled = false
        this.availability.recheckAvailability()

        assert !this.availability.isAvailable()
        assert this.collection.existsCalls == 0
    }
}
