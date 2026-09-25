package ro.editii.scriptorium.vector

import org.junit.jupiter.api.Test
import org.springframework.web.client.RestTemplate
import ro.editii.scriptorium.health.OllamaHealthTracker

import static org.mockito.Mockito.mock

/** Guards the model/collection pairing without starting Spring or opening network clients. */
class VectorConfigWiringTest {

    @Test
    void semanticSearchUsesTheBgeM3CollectionPair() {
        final config = new VectorConfig()
        // VectorConfig now parses a URI, so the scheme is part of the
        // contract (the application value is EMBEDDER_URL).
        final embedder = config.bgeM3Embedder("http://unused.invalid:11434", mock(RestTemplate.class), new OllamaHealthTracker())
        final collection = config.prodCollection(mock(MilvusService.class), "biblioteca_paras_bge_m3")

        assert embedder.modelName() == "BGE_M3"
        assert embedder.vectorDimension() == MilvusCollection.DIM_1024
        assert collection.name == "biblioteca_paras_bge_m3"
    }

    // EMBEDDER_URL/MILVUS_URL's pass-store value is a real "http://host:port"
    // URL (unlike the older bare "host:port" EMBEDDER_ADDRESS/MILVUS_ADDRESS
    // convention the test above still covers) - hostOf/portOf must strip
    // that scheme rather than treat "http://host" as the host.
    @Test
    void toleratesASchemePrefixOnTheAddress() {
        final config = new VectorConfig()
        final embedder = (OllamaEmbedder) config.bgeM3Embedder("http://unused.invalid:11434", mock(RestTemplate.class), new OllamaHealthTracker())

        assert embedder.host == "unused.invalid"
        assert embedder.port == 11434
    }
}
