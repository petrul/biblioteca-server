package ro.editii.scriptorium.vector

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.RestTemplate
import ro.editii.scriptorium.health.OllamaHealthTracker

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

/**
 * Unit-tests each Ollama-backed Embedder's request construction and
 * response parsing against a mocked HTTP transport (MockRestServiceServer -
 * real JSON (de)serialization, no real Ollama call). Dimension mapping,
 * per-text vector distinctness, and single-vs-batch consistency are all
 * verified here without depending on a live, sometimes-busy shared Ollama
 * instance.
 *
 * This is deliberately NOT the place that verifies Ollama (or SearXNG)
 * actually work for real end-to-end - that's the one job of
 * AuthorWorkEnrichmentTest (@Tag("integration-test")), the only test in this whole suite
 * that makes a real network call to either. See its doc comment.
 */
class OllamaEmbeddersTest {

    private static final String HOST = "fake-ollama.invalid"
    private static final int PORT = 11434
    private static final String[] SENTENCES = ["hello there", "how are you", "comment allez-vous?", "ce faci, bă?"]

    // Deterministic per-text vector: same text always maps to the same
    // vector (so a single-call and a batched-call embedding of the same
    // text compare as identical, same as cosineSimilarity's real-encoder
    // "same text, same point in space" contract below), distinct texts
    // reliably map to different vectors.
    private static List<Double> vectorFor(String text, int dim) {
        final seed = text.hashCode()
        (0..<dim).collect { i -> ((seed * 31 + i) % 1000) / 1000.0d }
    }

    private static String embedResponseJson(String[] texts, int dim) {
        JsonOutput.toJson([
                model     : "whatever",
                embeddings: texts.collect { vectorFor(it, dim) },
        ])
    }

    private static RestTemplate mockedRestTemplate(String[] expectedTexts, int dim) {
        final restTemplate = new RestTemplate()
        MockRestServiceServer.bindTo(restTemplate).build()
                .expect(requestTo("http://${HOST}:${PORT}/api/embed"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(JsonOutput.toJson(["input": expectedTexts])))
                .andRespond(withSuccess(embedResponseJson(expectedTexts, dim), MediaType.APPLICATION_JSON))
        return restTemplate
    }

    private static OllamaEmbedder embedderFor(String model, String modelName, int dim, RestTemplate restTemplate) {
        new OllamaEmbedder(HOST, PORT, model, modelName, dim, restTemplate, new OllamaHealthTracker())
    }

    @Test
    void bgeM3ProducesExpectedDimensionAndIsTheConfiguredMultilingualModel() {
        final restTemplate = mockedRestTemplate(SENTENCES, MilvusCollection.DIM_1024)
        final bgeM3Embedder = embedderFor("bge-m3", "BGE_M3", MilvusCollection.DIM_1024, restTemplate)

        assert bgeM3Embedder.modelName() == "BGE_M3"
        assert bgeM3Embedder.vectorDimension() == MilvusCollection.DIM_1024
        final vectors = bgeM3Embedder.encode(SENTENCES)
        assert vectors.length == SENTENCES.length
        vectors.each { assert it.length == MilvusCollection.DIM_1024 }
    }

    // A single-text call and a batched call embed the same text through a
    // different-shaped forward pass on a real encoder (padding/batch
    // dimensions differ), so a real Ollama server doesn't return
    // bit-identical vectors for the two - compare by cosine similarity
    // there, not equality. This mock returns the exact same deterministic
    // vector for a given text regardless of batch shape, so cosine
    // similarity is trivially 1.0 - the assertion below still documents
    // the real contract this embedder is expected to satisfy.
    private static double cosineSimilarity(float[] a, float[] b) {
        assert a.length == b.length
        double dot = 0, normA = 0, normB = 0
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB))
    }

    @Test
    void qwen3EmbeddingProducesExpectedDimensionAndDistinctVectors() {
        final batchRestTemplate = mockedRestTemplate(SENTENCES, 2560)
        final qwen3Embedder = embedderFor("qwen3-embedding:4b", "QWEN3_EMBEDDING_4B", 2560, batchRestTemplate)

        assert qwen3Embedder.modelName() == "QWEN3_EMBEDDING_4B"

        final vectors = qwen3Embedder.encode(SENTENCES)
        assert vectors.length == SENTENCES.length
        vectors.each { assert it.length == 2560 }
        assert vectors[0] != vectors[1]

        final singleRestTemplate = mockedRestTemplate([SENTENCES[0]] as String[], 2560)
        final qwen3EmbedderSingle = embedderFor("qwen3-embedding:4b", "QWEN3_EMBEDDING_4B", 2560, singleRestTemplate)
        final single = qwen3EmbedderSingle.encode(SENTENCES[0])
        assert single.length == 2560
        assert cosineSimilarity(single, vectors[0]) > 0.999
    }

    @Test
    void nomicEmbedProducesExpectedDimensionAndDistinctVectors() {
        final batchRestTemplate = mockedRestTemplate(SENTENCES, 768)
        final nomicEmbedder = embedderFor("nomic-embed-text", "NOMIC_EMBED_TEXT", 768, batchRestTemplate)

        assert nomicEmbedder.modelName() == "NOMIC_EMBED_TEXT"

        final vectors = nomicEmbedder.encode(SENTENCES)
        assert vectors.length == SENTENCES.length
        vectors.each { assert it.length == 768 }
        assert vectors[0] != vectors[1]

        final singleRestTemplate = mockedRestTemplate([SENTENCES[0]] as String[], 768)
        final nomicEmbedderSingle = embedderFor("nomic-embed-text", "NOMIC_EMBED_TEXT", 768, singleRestTemplate)
        final single = nomicEmbedderSingle.encode(SENTENCES[0])
        assert single.length == 768
        assert cosineSimilarity(single, vectors[0]) > 0.999
    }

    @Test
    void theTwoEmbeddersProduceDifferentlyShapedVectorsForTheSameText() {
        final text = SENTENCES[0]
        final qwenRestTemplate = mockedRestTemplate([text] as String[], 2560)
        final nomicRestTemplate = mockedRestTemplate([text] as String[], 768)

        final qwenVector = embedderFor("qwen3-embedding:4b", "QWEN3_EMBEDDING_4B", 2560, qwenRestTemplate).encode(text)
        final nomicVector = embedderFor("nomic-embed-text", "NOMIC_EMBED_TEXT", 768, nomicRestTemplate).encode(text)

        assert qwenVector.length != nomicVector.length
    }
}
