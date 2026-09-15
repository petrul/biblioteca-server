package ro.editii.scriptorium.vector;

import lombok.Getter;
import lombok.ToString;
import org.springframework.web.client.RestTemplate;
import ro.editii.scriptorium.health.OllamaHealthTracker;

import java.util.List;
import java.util.Map;

/**
 * Embedder backed by an Ollama server's batch-capable POST /api/embed
 * endpoint (not the older singular /api/embeddings, which only takes one
 * prompt at a time and returns a single vector).
 *
 * Unlike EmbeddingsClient/EmbeddingsModel - a fixed catalog of models on a
 * dedicated sentence-transformers server - any model already pulled on the
 * Ollama server can be used here just by name, so one class serves every
 * Ollama-backed embedding model; see VectorConfig for the actual configured
 * instances (Qwen3-Embedding-4B, nomic-embed-text).
 */
@ToString(onlyExplicitlyIncluded = true)
public class OllamaEmbedder implements Embedder {

    private final RestTemplate restTemplate;
    private final OllamaHealthTracker healthTracker;
    @Getter
    @ToString.Include
    private final String host;
    @Getter
    @ToString.Include
    private final int port;
    @Getter
    @ToString.Include
    private final String model;
    @ToString.Include
    private final String modelName;
    @ToString.Include
    private final int vectorDimension;

    public OllamaEmbedder(String host, int port, String model, String modelName, int vectorDimension,
                           RestTemplate restTemplate, OllamaHealthTracker healthTracker) {
        this.host = host;
        this.port = port;
        this.model = model;
        this.modelName = modelName;
        this.vectorDimension = vectorDimension;
        this.restTemplate = restTemplate;
        this.healthTracker = healthTracker;
    }

    @Override
    public String modelName() {
        return this.modelName;
    }

    @Override
    public int vectorDimension() {
        return this.vectorDimension;
    }

    @Override
    public String describe() {
        return String.format("Ollama encoder \"%s\" (%s) at %s:%d, dim=%d", this.modelName, this.model, this.host, this.port, this.vectorDimension);
    }

    @Override
    public float[][] encode(String[] texts) {
        // Fails immediately, no network attempt at all, while Ollama's
        // known stuck - callers (MilvusTextSearchService,
        // SearchRestController's /api/search/ann) already bound the
        // actual attempt with their own timeout, but skipping it
        // entirely turns a guaranteed ~15s wait into an instant one for
        // every request until the cooldown elapses.
        if (!this.healthTracker.isAvailable())
            throw new IllegalStateException("Ollama at " + this.host + ":" + this.port + " is marked unavailable (cooling down)");

        final Map<String, Object> request = Map.of(
                "model", this.model,
                "input", List.of(texts)
        );
        final EmbedResponse resp;
        try {
            resp = this.restTemplate.postForObject(getUrl("/api/embed"), request, EmbedResponse.class);
        } catch (Exception e) {
            this.healthTracker.markUnavailable();
            throw e;
        }
        if (resp == null || resp.embeddings() == null) {
            this.healthTracker.markUnavailable();
            throw new IllegalStateException("Ollama /api/embed returned no embeddings for model '" + this.model + "'");
        }
        this.healthTracker.markAvailable();

        final List<List<Double>> embeddings = resp.embeddings();
        final float[][] out = new float[embeddings.size()][];
        for (int i = 0; i < embeddings.size(); i++) {
            final List<Double> vec = embeddings.get(i);
            final float[] arr = new float[vec.size()];
            for (int j = 0; j < vec.size(); j++) arr[j] = vec.get(j).floatValue();
            out[i] = arr;
        }
        return out;
    }

    private String getUrl(String path) {
        if (!path.startsWith("/"))
            path = "/" + path;
        return String.format("http://%s:%d%s", this.host, this.port, path);
    }

    /** Shape of Ollama's POST /api/embed JSON response (ignoring the timing/count fields we don't need). */
    private record EmbedResponse(String model, List<List<Double>> embeddings) {}
}
