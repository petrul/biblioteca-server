package ro.editii.scriptorium.vector

import io.milvus.client.MilvusServiceClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.web.client.RestTemplate

import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

/**
 * Prevents unrelated Spring component/integration tests from opening a gRPC
 * channel, an HTTP connection or probing the vector store during
 * ApplicationReadyEvent. An accidental vector operation fails locally
 * through an unstubbed mock rather than silently reaching a developer's
 * shared integration environment. Covers BOTH stores' beans: the default
 * (qdrant, whose QdrantCollection speaks HTTP through qdrantRestTemplate)
 * and the milvus one behind vector.store=milvus.
 */
@TestConfiguration
class NetworkFreeVectorTestConfig {

    @Bean(name = "milvusClient")
    @Primary
    MilvusServiceClient milvusClient() {
        return mock(MilvusServiceClient)
    }

    @Bean(name = "milvusService")
    @Primary
    MilvusService milvusService() {
        final service = mock(MilvusService)
        when(service.has(anyString())).thenReturn(false)
        return service
    }

    // Same bean NAME as VectorConfig's - with the tests'
    // spring.main.allow-bean-definition-overriding=true this replaces the
    // real one. Deliberately NOT @Primary: another RestTemplate elsewhere
    // already carries @Primary, and two primaries make every
    // RestTemplate-by-type injection ambiguous (observed: context load
    // failure in LuceneSearchITest).
    @Bean(name = "qdrantRestTemplate")
    RestTemplate networkFreeQdrantRestTemplate() {
        // Belt: the actual guard is the primary VectorCollection fake
        // below - this only bounds the (already non-connecting)
        // QdrantCollection bean's template should anything ever call it.
        final factory = new org.springframework.http.client.SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(100)
        factory.setReadTimeout(100)
        return new RestTemplate(factory)
    }

    /**
     * Braces: whatever store vector.store selects, THIS is the
     * VectorCollection the app sees in tests - a Groovy map-coerced fake
     * whose exists() is false, so VectorSearchAvailability's
     * ApplicationReadyEvent check flips vector search off without a
     * single network byte. An accidental vector operation fails through
     * the fake's UnsupportedOperationException rather than silently
     * reaching a developer's shared integration environment. The name is
     * the context's own vector.collection value, so
     * VectorTextSearchService's model-name compatibility check keeps
     * exercising the collection the test configured, same as the real
     * beans would.
     */
    @Bean
    @Primary
    VectorCollection networkFreeVectorCollection(
            @Value('${vector.collection:biblioteca_paras_bge_m3}') String collectionName) {
        return [
                getName            : { collectionName },
                exists             : { false },
                create             : { int dim, String description -> throw new UnsupportedOperationException("network-free test") },
                getVectorDimension : { throw new UnsupportedOperationException("network-free test") },
                findBySha256       : { String sha -> throw new UnsupportedOperationException("network-free test") },
                searchHits         : { float[] vector, int topK -> throw new UnsupportedOperationException("network-free test") },
        ] as VectorCollection
    }
}
