package ro.editii.scriptorium.vector

import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.RestTemplate
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

/**
 * MockRestServiceServer-based unit test (the same harness OllamaEmbeddersTest
 * uses - a real RestTemplate with stubbed HTTP, no Mockito, no live Qdrant)
 * for QdrantCollection: the REST shapes it speaks and the responses it
 * parses, so the store-agnostic search/availability layers can rely on it
 * without a live instance.
 */
class QdrantCollectionTest {

    static final String BASE = "http://qdrant.invalid:6333"

    static QdrantCollection collection(RestTemplate rest) {
        return new QdrantCollection(BASE, "test_bge_m3", rest)
    }

    @Test
    void existsIsTrueFor200AndFalseFor404() {
        final rest = new RestTemplate()
        MockRestServiceServer.bindTo(rest).build().expect(requestTo("${BASE}/collections/test_bge_m3"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess('{"result":{}}', MediaType.APPLICATION_JSON))
        assert collection(rest).exists()

        // A second, separate instance for the 404 case (the server stub is
        // single-use per expectation).
        final rest404 = new RestTemplate()
        MockRestServiceServer.bindTo(rest404).build().expect(requestTo("${BASE}/collections/test_bge_m3"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND))
        assertFalse(collection(rest404).exists())
    }

    @Test
    void createBuildsHnswEuclidInt8CollectionWithPayloadIndexes() {
        final rest = new RestTemplate()
        final server = MockRestServiceServer.bindTo(rest).build()

        // The dense collection config - HNSW + Euclid + int8, the qdrant
        // counterpart of the Milvus IVF_SQ8/L2 rationale (see
        // QdrantCollection's class comment).
        server.expect(requestTo("${BASE}/collections/test_bge_m3")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json('''{
                    "vectors": {"embedding": {"size": 1024, "distance": "Euclid"}},
                    "hnsw_config": {"m": 16, "ef_construct": 100},
                    "quantization_config": {"scalar": {"type": "int8", "always_ram": true}}
                }'''))
                .andRespond(withSuccess('{"result":true}', MediaType.APPLICATION_JSON))
        // Exact-match keyword payload indexes on sha256 and url - and no
        // full-text/sparse index (IR search stays Lucene's job).
        server.expect(requestTo("${BASE}/collections/test_bge_m3/index")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json('{"field_name": "sha256", "field_schema": "keyword"}'))
                .andRespond(withSuccess('{"result":true}', MediaType.APPLICATION_JSON))
        server.expect(requestTo("${BASE}/collections/test_bge_m3/index")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json('{"field_name": "url", "field_schema": "keyword"}'))
                .andRespond(withSuccess('{"result":true}', MediaType.APPLICATION_JSON))

        collection(rest).create(1024, "bge-m3 via Ollama")
    }

    @Test
    void getVectorDimensionReadsTheNamedVectorSize() {
        final rest = new RestTemplate()
        MockRestServiceServer.bindTo(rest).build().expect(requestTo("${BASE}/collections/test_bge_m3"))
                .andRespond(withSuccess(
                '{"result":{"config":{"params":{"vectors":{"embedding":{"size":1024,"distance":"Euclid"}}}}}}',
                MediaType.APPLICATION_JSON))
        assertEquals(1024, collection(rest).getVectorDimension())
    }

    @Test
    void searchHitsParsesScoresAndPayloadInRankingOrder() {
        final rest = new RestTemplate()
        MockRestServiceServer.bindTo(rest).build()
                .expect(requestTo("${BASE}/collections/test_bge_m3/points/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                '''{"result":[
                     {"id":"11111111-1111-1111-1111-111111111111","score":12.5,
                      "payload":{"sha256":"aaa","url":"http://x/a"}},
                     {"id":"22222222-2222-2222-2222-222222222222","score":40.0,
                      "payload":{"sha256":"bbb","url":"http://x/b"}}]}''',
                MediaType.APPLICATION_JSON))

        final hits = collection(rest).searchHits([0.25f, 0.5f] as float[], 2)

        assertEquals(2, hits.size())
        assertEquals("aaa", hits[0].sha256())
        assertEquals("http://x/a", hits[0].url())
        assertEquals(12.5f, hits[0].score(), 0.0001f)
        assertEquals("bbb", hits[1].sha256())
        assertEquals(40.0f, hits[1].score(), 0.0001f)
    }

    @Test
    void findBySha256ReturnsTheStoredVectorOrNullForMissing() {
        final rest = new RestTemplate()
        // Both expectations up front - a MockRestServiceServer cannot add
        // expectations after requests have been made.
        final server = MockRestServiceServer.bindTo(rest).build()
        server.expect(requestTo("${BASE}/collections/test_bge_m3/points"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                '''{"result":[{"id":"00000000-0000-0000-0000-0000000000ff",
                     "payload":{"sha256":"abff","url":"http://x/a"},
                     "vector":{"embedding":[0.25,0.5]}}]}''',
                MediaType.APPLICATION_JSON))
        server.expect(requestTo("${BASE}/collections/test_bge_m3/points"))
                .andRespond(withSuccess('{"result":[]}', MediaType.APPLICATION_JSON))

        final stored = collection(rest).findBySha256("abff")
        assertEquals("abff", stored.getSha256())
        assertEquals("http://x/a", stored.getUrl())
        assertEquals(2, stored.getEmbedding().length)
        assertEquals(0.25f, stored.getEmbedding()[0], 0.0001f)

        // Unknown sha256: Qdrant answers an empty result array - null, not an error.
        assertNull(collection(rest).findBySha256("missing"))
    }

    @Test
    void aDeadStoreFailsLoudlyInsteadOfReturningGarbage() {
        final rest = new RestTemplate()
        MockRestServiceServer.bindTo(rest).build()
                .expect(requestTo("${BASE}/collections/test_bge_m3/points/search"))
                .andRespond(withServerError())

        assertThrows(RuntimeException.class) {
            collection(rest).searchHits([0.25f] as float[], 2)
        }
    }
}
