package ro.editii.scriptorium.vector

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito

import static org.mockito.Mockito.mock

/**
 * Pure Mockito unit test (no Spring context - same harness as
 * MilvusTextSearchServiceTest) for VectorSearchAvailability's periodic
 * recheckAvailability(): the counterpart of the one-shot startup check
 * that flips the availability flag within one interval of Milvus going
 * away or coming back, so neither direction needs a server restart
 * anymore (a Milvus outage at boot used to keep search silently off for
 * the whole run).
 */
class VectorSearchAvailabilityTest {

    MilvusService milvusService
    MilvusCollection collection
    Embedder embedder
    VectorSearchAvailability availability

    @BeforeEach
    void setUp() {
        this.milvusService = mock(MilvusService.class)
        this.collection = mock(MilvusCollection.class)
        this.embedder = mock(Embedder.class)
        this.availability = new VectorSearchAvailability(milvusService, collection, embedder)
        this.availability.availabilityCheckEnabled = true
    }

    @Test
    void recheckFlipsOffWhenMilvusIsGoneAndBackWhenItReturns() {
        // As left by a successful startup check.
        this.availability.available = true

        // Milvus gone: the check itself blows up (connection refused), not
        // just returns false - the recheck must swallow that and flip off.
        // (doThrow/doReturn rather than when().thenThrow(): re-stubbing a
        // method through when() first invokes the OLD stub - which throws
        // straight out of the stubbing call itself.)
        Mockito.doThrow(new IllegalStateException("connection refused")).when(milvusService).has(Mockito.any())
        this.availability.recheckAvailability()
        assert !this.availability.isAvailable()

        // Still gone: no change (and no per-cycle log - transitions only).
        this.availability.recheckAvailability()
        assert !this.availability.isAvailable()

        // Milvus back: collection present and the vector dimension agrees
        // with the embedder's - the recheck flips search on again.
        Mockito.doReturn(true).when(milvusService).has(Mockito.any())
        Mockito.when(collection.getVectorDimension()).thenReturn(1024)
        Mockito.when(embedder.vectorDimension()).thenReturn(1024)
        this.availability.recheckAvailability()
        assert this.availability.isAvailable()
    }

    @Test
    void recheckLeavesSearchOffWhenTheCheckIsDisabled() {
        this.availability.available = false

        // Milvus is perfectly reachable, but the check is disabled - the
        // recheck must not resurrect search behind the configured flag's
        // back, nor touch Milvus at all.
        this.availability.availabilityCheckEnabled = false
        this.availability.recheckAvailability()

        assert !this.availability.isAvailable()
        Mockito.verifyNoInteractions(milvusService)
        Mockito.verifyNoInteractions(collection)
        Mockito.verifyNoInteractions(embedder)
    }
}
