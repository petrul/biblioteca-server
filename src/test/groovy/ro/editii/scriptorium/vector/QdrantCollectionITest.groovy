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
// Never leave the suite waiting indefinitely for the store.
@Timeout(30L)
class QdrantCollectionITest {

    static QdrantCollection connect(String name) {
        final address = System.getenv('VECTORSTORE_URL') ?: System.getenv('MILVUS_URL')
        Assumptions.assumeTrue(address != null, 'no VECTORSTORE_URL/MILVUS_URL configured - nothing to test against')
        final factory = new SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(2_000)
        factory.setReadTimeout(5_000)
        return new QdrantCollection(address, name, new RestTemplate(factory))
    }

    static Content content(String sha, String url, float[] embedding) {
        return Content.builder().sha256(sha).url(url).embedding(embedding).build()
    }

    @Test
    void createUpsertSearchReadBackAndDrop() {
        final col = connect('int-test_qdrant_itest_' + TestUtils.randomString(10))
        try {
            col.create(4, "qdrant itest fixture - safe to delete")
        } catch (Exception unreachable) {
            Assumptions.assumeTrue(false, "Qdrant is unavailable at the configured endpoint: ${unreachable.message}")
        }

        try {
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
        } finally {
            col.drop()
            assert !col.exists()
        }
    }
}
