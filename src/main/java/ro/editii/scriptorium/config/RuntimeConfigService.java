package ro.editii.scriptorium.config;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory, allow-listed configuration for operational changes while the
 * server is running.  It deliberately does not write .env files or the pass
 * store.  The map is also installed as the first Spring property source, so
 * code which reads the Environment after an update sees the new value.
 *
 * Components constructed at startup (a datasource, Kafka client, or an
 * embedder) are not rebuilt by this service; those values are marked as
 * restartRequired in the API response.  This avoids pretending that changing
 * a URL can safely mutate a live connection pool.
 */
@Service
public class RuntimeConfigService {
    private static final String PROPERTY_SOURCE = "bibliotecaRuntimeConfig";

    private record Spec(String key, String environmentVariable, boolean sensitive, boolean restartRequired) {
    }

    private static final List<Spec> SPECS = List.of(
            new Spec("spring.datasource.url", "DB_URL", true, true),
            new Spec("spring.kafka.bootstrap-servers", "KAFKA_BROKERS", false, true),
            new Spec("vectorstore.address", "VECTORSTORE_URL", false, true),
            new Spec("embedder.address", "EMBEDDER_URL", false, true),
            // Older profiles call this OLLAMA_URL/OLLAMA_SERVER even though
            // the current vector wiring consumes the split host/port pair.
            // Keep both names visible for operational inspection and future
            // wiring without silently accepting arbitrary environment keys.
            new Spec("ollama.url", "OLLAMA_URL", false, true),
            new Spec("ollama.server", "OLLAMA_SERVER", false, true),
            new Spec("ollama.host", "OLLAMA_HOST", false, true),
            new Spec("ollama.port", "OLLAMA_PORT", false, true),
            new Spec("sts.host", "STS_HOST", false, true),
            new Spec("sts.port", "STS_PORT", false, true),
            new Spec("repo.tei.repos", "TEI_REPOS", false, true),
            new Spec("work.dir", "WORK_DIR", false, true),
            new Spec("vector.store", "VECTOR_STORE", false, true),
            new Spec("vector.collection", "VECTOR_COLLECTION", false, true),
            new Spec("vector.collection.prefix", "VECTOR_COLLECTION_PREFIX", false, true),
            new Spec("vectorizer.para.minChars", "VECTORIZER_PARA_MIN_CHARS", false, true),
            new Spec("vectorizer.para.maxChars", "VECTORIZER_PARA_MAX_CHARS", false, true),
            new Spec("lucene.index.dir", "LUCENE_INDEX_DIR", false, true),
            new Spec("lucene.autoindex.enabled", "LUCENE_AUTOINDEX_ENABLED", false, true)
    );

    private final ConfigurableEnvironment environment;
    private final Map<String, Spec> specs;
    private final Map<String, String> startupValues;
    private final ConcurrentMap<String, Object> runtimeValues = new ConcurrentHashMap<>();

    public RuntimeConfigService(ConfigurableEnvironment environment) {
        this.environment = environment;
        final Map<String, Spec> byKey = new LinkedHashMap<>();
        final Map<String, String> initialValues = new LinkedHashMap<>();
        for (Spec spec : SPECS) {
            byKey.put(spec.key(), spec);
            final String value = firstPresent(environment.getProperty(spec.environmentVariable()),
                    safeProperty(spec.key()),
                    // The historic vector-store pass-store key remains a
                    // supported input while deployments migrate to the
                    // canonical VECTORSTORE_URL name.
                    "vectorstore.address".equals(spec.key()) ? environment.getProperty("MILVUS_URL") : null);
            if (value != null) {
                initialValues.put(spec.key(), value);
                runtimeValues.put(spec.key(), value);
                runtimeValues.put(spec.environmentVariable(), value);
            }
        }
        this.specs = Collections.unmodifiableMap(byKey);
        this.startupValues = Collections.unmodifiableMap(initialValues);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, runtimeValues));
    }

    public List<RuntimeConfigEntry> snapshot(boolean revealSensitive) {
        final List<RuntimeConfigEntry> result = new ArrayList<>();
        for (Spec spec : specs.values()) {
            result.add(entry(spec, revealSensitive));
        }
        return result;
    }

    public RuntimeConfigEntry get(String requestedKey, boolean revealSensitive) {
        final Spec spec = spec(requestedKey);
        return entry(spec, revealSensitive);
    }

    /** Internal consumers can read the unmasked value without exposing it on the wire. */
    public String value(String requestedKey) {
        return specValue(spec(requestedKey));
    }

    public RuntimeConfigEntry set(String requestedKey, String value, boolean revealSensitive) {
        final Spec spec = spec(requestedKey);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Runtime configuration values must not be blank");
        }
        runtimeValues.put(spec.key(), value);
        runtimeValues.put(spec.environmentVariable(), value);
        return entry(spec, revealSensitive);
    }

    public RuntimeConfigEntry reset(String requestedKey, boolean revealSensitive) {
        final Spec spec = spec(requestedKey);
        runtimeValues.remove(spec.key());
        runtimeValues.remove(spec.environmentVariable());
        final String initial = startupValues.get(spec.key());
        if (initial != null) {
            runtimeValues.put(spec.key(), initial);
            runtimeValues.put(spec.environmentVariable(), initial);
        }
        return entry(spec, revealSensitive);
    }

    private RuntimeConfigEntry entry(Spec spec, boolean revealSensitive) {
        final String actual = specValue(spec);
        final String displayed = actual == null ? null : (spec.sensitive() && !revealSensitive ? "********" : actual);
        return new RuntimeConfigEntry(spec.key(), spec.environmentVariable(), displayed,
                actual != null, spec.sensitive(), spec.restartRequired());
    }

    private String specValue(Spec spec) {
        final Object raw = runtimeValues.get(spec.key());
        return raw == null ? null : raw.toString();
    }

    private Spec spec(String requestedKey) {
        if (requestedKey == null) {
            throw new IllegalArgumentException("Configuration key is required");
        }
        final Spec direct = specs.get(requestedKey);
        if (direct != null) {
            return direct;
        }
        return specs.values().stream()
                .filter(it -> it.environmentVariable().equals(requestedKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported runtime configuration key: " + requestedKey));
    }

    private String safeProperty(String key) {
        try {
            return environment.getProperty(key);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return null;
        }
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
