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
        final collection = config.prodCollection(mock(MilvusService.class), "int_tb_paras_bge_m3")

        assert embedder.modelName() == "BGE_M3"
        assert embedder.vectorDimension() == MilvusCollection.DIM_1024
        assert collection.name == "int_tb_paras_bge_m3"
    }
}
