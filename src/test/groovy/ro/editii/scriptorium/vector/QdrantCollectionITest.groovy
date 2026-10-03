package ro.editii.scriptorium.vector

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestTemplate
import ro.editii.scriptorium.TestUtils

/**
 * The qdrant counterpart of the milvus integration tests: against a real
 * instance, create a temporary collection, write points, search them,
 * read one back by hash, and drop the collection - proving the whole
 * VectorCollection contract end to end.
 *
 * Points at the shared prod qdrant instance (one instance serves every
 * environment - see vector.collection.prefix) through VECTORSTORE_URL,
 * with MILVUS_URL as the not-yet-renamed fallback: the pass store's key
 * will carry the qdrant address once renamed. The temporary collection
 * name is int-test_-prefixed plus random, so the test can never touch a
 * real stage's data - and a leftover from a failed run is recognizable
 * and deletable by that same prefix.
 *
 * Skips (not fails) when the store is unreachable, same as the milvus
 * integration tests do.
 */
@Tag("integration-test")
// Collection creation on the shared instance can take tens of seconds
// while the vectorizer is bulk-upserting (measured: PUT /collections/<n>
// took 27s against a store taking ~40 points/s) - the suite must never
// leave the store's own slowness as a false "unavailable" skip, nor wait
// indefinitely either.
@Timeout(180L)
class QdrantCollectionITest {

    static QdrantCollection connect(String name) {
        final address = System.getenv('VECTORSTORE_URL') ?: System.getenv('MILVUS_URL')
        Assumptions.assumeTrue(address != null, 'no VECTORSTORE_URL/MILVUS_URL configured - nothing to test against')
        final factory = new SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(2_000)
        // Generous on purpose (see the class comment): collection
        // create/drop are metadata-heavy operations that queue behind the
        // vectorizer's bulk writes on the shared instance. The app's own
        // qdrantRestTemplate stays at 5s - searches are reads and stay
        // fast; only this lifecycle machinery needs the headroom.
        factory.setReadTimeout(60_000)
        return new QdrantCollection(address, name, new RestTemplate(factory))
    }

    static Content content(String sha, String url, float[] embedding) {
        return Content.builder().sha256(sha).url(url).embedding(embedding).build()
    }

    @Test
    void createUpsertSearchReadBackAndDrop() {
        final col = connect('int-test_qdrant_itest_' + TestUtils.randomString(10))
        // The collection must never outlive the test - not on an assertion
        // failure, not on an aborted unavailable-store assumption, not on
        // a create() that landed the collection but failed a later step
        // (its index PUTs can fail individually). The drop lives in the
        // OUTER finally so every one of those paths still cleans up, and
        // a failed drop never masks the original failure: rethrown only
        // when the body itself was green, logged otherwise.
        Throwable body = null
        try {
            try {
                try {
                    col.create(4, "qdrant itest fixture - safe to delete")
                } catch (Exception unreachable) {
                    Assumptions.assumeTrue(false, "Qdrant is unavailable at the configured endpoint: ${unreachable.message}")
                }

                assert col.exists()
                assert col.getVectorDimension() == 4

                // A tiny, hand-computable 4-dim space: near / nearer / far.
                final shaA = 'a' * 64
                final shaB = 'b' * 64
                final shaC = 'c' * 64
                col.upsert([
                        content(shaA, 'http://test/a/opus/paragraph_1', [1.0f, 0f, 0f, 0f] as float[]),
                        content(shaB, 'http://test/a/opus/paragraph_2', [1.1f, 0f, 0f, 0f] as float[]),
                        content(shaC, 'http://test/c/opus/paragraph_1', [5.0f, 5.0f, 5.0f, 5.0f] as float[]),
                ] as Content[])

                // Search from A's own position: A itself is nearest (distance 0),
                // B second (0.01), C far - ranking order is the whole claim.
                final hits = col.searchHits([1.0f, 0f, 0f, 0f] as float[], 3)
                assert hits.size() == 3
                assert hits[0].sha256() == shaA
                assert hits[0].score() == 0f
                assert hits[1].sha256() == shaB
                assert hits[2].sha256() == shaC
                assert hits[0].score() < hits[2].score()

                // Read one back by hash: the stored vector and url survive.
                final stored = col.findBySha256(shaB)
                assert stored != null
                assert stored.getUrl() == 'http://test/a/opus/paragraph_2'
                assert stored.getEmbedding().length == 4
                assert stored.getEmbedding()[0] == 1.1f

                // And an unknown hash is null, not an error.
                assert col.findBySha256('f' * 64) == null

                // Point-count visibility - the qdrant counterpart of the
                // retired milvus experiment suite's row_count assertion:
                // upserts above carry wait=true, so all three points must be
                // immediately visible in the collection stats.
                final count = pointCount(col)
                assert count == 3 : "expected 3 points, collection reports ${count}"
            } catch (Throwable t) {
                body = t
                throw t
            }
        } finally {
            dropBestEffort(col, body)
        }
    }

    /**
     * Drop the test collection even when the test failed or was skipped,
     * without ever letting a cleanup failure mask the original one: on a
     * green run the drop must succeed (its failure fails the test), on a
     * failed/skipped run the drop is best-effort and only logged - the
     * int-test_ prefix keeps a straggler recognizable and sweepable.
     */
    private static void dropBestEffort(QdrantCollection col, Throwable body) {
        try {
            if (col.exists()) col.drop()
            assert !col.exists()
        } catch (Throwable cleanup) {
            if (body == null) throw cleanup
            System.err.println("qdrant itest: dropping ${col.name} failed after the test had already failed (${body}) - the collection may need a manual int-test_ prefix sweep: ${cleanup.message}")
        }
    }

    /** The collection's points_count, via the same RestTemplate the collection uses. */
    private static long pointCount(QdrantCollection col) {
        final address = System.getenv('VECTORSTORE_URL') ?: System.getenv('MILVUS_URL')
        final restTemplate = new RestTemplate()
        final Map<String, Object> info = restTemplate.getForObject(
                "${address}/collections/${col.name}", Map.class)
        final Object result = info?.get('result')
        final count = result instanceof Map ? ((Map) result).get('points_count') : null
        return count != null ? ((Number) count).longValue() : -1L
    }
}
