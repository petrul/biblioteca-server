package ro.editii.scriptorium.vector

import org.junit.jupiter.api.Test
import org.springframework.web.client.RestTemplate

import static org.mockito.Mockito.mock

/** Guards the model/collection pairing without starting Spring or opening network clients. */
class VectorConfigWiringTest {

    @Test
    void semanticSearchUsesTheBgeM3CollectionPair() {
        final config = new VectorConfig()
        final embedder = config.bgeM3Embedder("unused.invalid:11434", mock(RestTemplate.class))
        final collection = config.prodCollection(mock(MilvusService.class), "int_tb_paras_bge_m3")

        assert embedder.modelName() == "BGE_M3"
        assert embedder.vectorDimension() == MilvusCollection.DIM_1024
        assert collection.name == "int_tb_paras_bge_m3"
    }
}
