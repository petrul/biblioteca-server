package ro.editii.scriptorium.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import ro.editii.scriptorium.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Qdrant-backed {@link VectorCollection} - the store behind
 * vector.store=qdrant. Implements the same contract MilvusCollection
 * does, so VectorTextSearchService/VectorSearchAvailability work
 * unchanged against either.
 *
 * Uses the REST API (the 6333 HTTP surface) rather than the official
 * gRPC client on purpose: no new dependency, and it matches how this
 * codebase already talks to its other HTTP services (Sts/Ollama
 * embedders) through a timeout-bounded RestTemplate (see VectorConfig's
 * qdrantRestTemplate) - a hung Qdrus can never hang a search request
 * or the availability check.
 *
 * Index choices (see create()):
 * - dense: HNSW (m=16, ef_construct=100), Euclid (L2) distance - the
 *   same lower-is-closer semantics as the Milvus L2 collections, so
 *   scores and ranking order are comparable across stores.
 * - scalar int8 quantization, always_ram: the qdrant counterpart of
 *   biblioteca-nestjs's IVF_SQ8 rationale - the target corpus at full
 *   precision would not fit the host's RAM loaded.
 * - keyword payload indexes on sha256 and url: exact-match lookups and
 *   filters without a scan.
 *
 * Qdrant's IR-grade options (full-text payload index, sparse
 * vectors/BM25-style scoring) are deliberately NOT configured here: this
 * deployment's information-retrieval search is Lucene's job (the
 * /api/search/lucene mode), and a qdrant sparse vector would need an
 * embedder emitting one (bge-m3's sparse output, not exposed through
 * Ollama's embeddings API). Both are documented paths if that changes.
 *
 * Qdrant point IDs must be UUIDs or unsigned ints, and a sha256 hex
 * digest is neither - points are therefore identified by the first 32
 * hex chars of the sha256 formatted as a UUID (deterministic in both
 * directions, collision-safe at 128 bits, and the actual sha256 travels
 * in the payload anyway - the id is an implementation detail).
 */
@Log4j2
public class QdrantCollection implements VectorCollection {

    public static final String FIELD_SHA_256 = "sha256";
    public static final String FIELD_URL = "url";
    // The named dense vector - mirrors MilvusCollection's FIELD_EMBEDDING.
    public static final String FIELD_EMBEDDING = "embedding";

    final String baseUrl;
    final String name;
    final RestTemplate rest;
    final ObjectMapper mapper = new ObjectMapper();

    public QdrantCollection(String address, String name, RestTemplate rest) {
        // Accept "host:port" and "http://host:port" alike - same leniency
        // as VectorConfig's milvus address handling.
        this.baseUrl = address.contains("://") ? address : "http://" + address;
        this.name = name;
        this.rest = rest;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public boolean exists() {
        try {
            getJson("/collections/" + this.name, null, HttpMethod.GET);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    /** Deletes the whole collection - the integration test's cleanup step. */
    public void drop() {
        execute("/collections/" + this.name, null, HttpMethod.DELETE);
    }

    @Override
    public void create(int vectorDimension, String description) {
        // Qdrant has no collection-description field - the encoder identity
        // already travels in the name (see VectorCollection), so the
        // description is accepted and dropped rather than silently lost
        // information.
        Util.assertTrue(description != null);
        final Map<String, Object> vector = new HashMap<>();
        vector.put("size", vectorDimension);
        vector.put("distance", "Euclid");
        final Map<String, Object> params = new HashMap<>();
        params.put("vectors", Map.of(FIELD_EMBEDDING, vector));
        params.put("hnsw_config", Map.of("m", 16, "ef_construct", 100));
        params.put("quantization_config", Map.of(
                "scalar", Map.of("type", "int8", "always_ram", true)));
        postJson("/collections/" + this.name, params, HttpMethod.PUT);

        // Exact-match payload indexes (see the class comment for why these,
        // and why no full-text/sparse index).
        postJson("/collections/" + this.name + "/index",
                Map.of("field_name", FIELD_SHA_256, "field_schema", "keyword"), HttpMethod.PUT);
        postJson("/collections/" + this.name + "/index",
                Map.of("field_name", FIELD_URL, "field_schema", "keyword"), HttpMethod.PUT);

        log.info("Created Qdrant collection {} (dim={}, HNSW/Euclid, int8)", this.name, vectorDimension);
    }

    @Override
    public int getVectorDimension() {
        final JsonNode root = getJson("/collections/" + this.name, null, HttpMethod.GET);
        return root.path("result").path("config").path("params").path("vectors")
                .path(FIELD_EMBEDDING).path("size").asInt(-1);
    }

    /**
     * Qdrant point IDs must be UUIDs, and a sha256 hex digest is not one -
     * the first 32 hex chars of the sha256, formatted as a UUID, are the
     * deterministic point ID (see the class comment). The actual sha256
     * still travels in the payload.
     */
    static String pointIdOf(String sha256) {
        final String hex = sha256.length() >= 32 ? sha256.substring(0, 32) : sha256;
        return hex.replaceFirst("^(.{8})(.{4})(.{4})(.{4})(.{12})$", "$1-$2-$3-$4-$5");
    }

    @Override
    public Content findBySha256(String sha256) {
        final JsonNode body = postJson("/collections/" + this.name + "/points",
                Map.of("ids", List.of(pointIdOf(sha256)), "with_payload", true, "with_vector", true),
                HttpMethod.POST).path("result");
        if (!body.isArray() || body.isEmpty())
            return null;
        final JsonNode point = body.get(0);
        final JsonNode vector = point.path("vector").path(FIELD_EMBEDDING);
        final float[] embedding = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++)
            embedding[i] = (float) vector.get(i).asDouble();
        return Content.builder()
                .sha256(point.path("payload").path(FIELD_SHA_256).asText(sha256))
                .url(point.path("payload").path(FIELD_URL).asText(null))
                .embedding(embedding)
                .build();
    }

    /**
     * Write path - deliberately NOT on the VectorCollection interface
     * (the server never writes; that's biblioteca-nestjs's job) but
     * needed by the integration test: create -> upsert -> search -> drop
     * against a real instance.
     */
    public void upsert(Content[] content) {
        final List<Map<String, Object>> points = new ArrayList<>();
        for (final Content it : content) {
            final List<Double> vector = new ArrayList<>(it.embedding.length);
            for (float v : it.embedding)
                vector.add((double) v);
            points.add(Map.of(
                    "id", pointIdOf(it.sha256),
                    "vector", Map.of(FIELD_EMBEDDING, vector),
                    "payload", Map.of(FIELD_SHA_256, it.sha256, FIELD_URL, it.url)));
        }
        postJson("/collections/" + this.name + "/points?wait=true", Map.of("points", points), HttpMethod.PUT);
    }

    public List<VectorSearchHit> searchHits(float[] vector, int topK) {
        final List<Double> asList = new ArrayList<>(vector.length);
        for (float v : vector)
            asList.add((double) v);

        final Map<String, Object> body = new HashMap<>();
        body.put("vector", Map.of("name", FIELD_EMBEDDING, "vector", asList));
        body.put("limit", topK);
        body.put("with_payload", true);

        final JsonNode result = postJson("/collections/" + this.name + "/points/search", body, HttpMethod.POST)
                .path("result");

        final List<VectorSearchHit> hits = new ArrayList<>();
        for (final JsonNode row : result) {
            hits.add(new VectorSearchHit(
                    row.path("payload").path(FIELD_SHA_256).asText(null),
                    row.path("payload").path(FIELD_URL).asText(null),
                    (float) row.path("score").asDouble()));
        }
        return hits;
    }

    private JsonNode getJson(String path, Object body, HttpMethod method) {
        return execute(path, body, method);
    }

    private JsonNode postJson(String path, Object body, HttpMethod method) {
        return execute(path, body, method);
    }

    private JsonNode execute(String path, Object body, HttpMethod method) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        final HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        final String response = this.rest.exchange(this.baseUrl + path, method, entity, String.class).getBody();
        try {
            return this.mapper.readTree(response == null ? "{}" : response);
        } catch (Exception e) {
            throw new RuntimeException("Qdrant returned a non-JSON response for " + path, e);
        }
    }

    @Override
    public String toString() {
        return "QdrantCollection{" + this.baseUrl + "/" + this.name + "}";
    }
}
