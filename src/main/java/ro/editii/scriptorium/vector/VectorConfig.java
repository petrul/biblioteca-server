package ro.editii.scriptorium.vector;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import java.util.concurrent.TimeUnit;
import ro.editii.scriptorium.client.TextbaseClient;
import ro.editii.scriptorium.health.OllamaHealthTracker;
import ro.editii.scriptorium.search.content.UrlContentResolver;

@Configuration
@Log4j2
public class VectorConfig {

    public static final String TEXTBASE_CLIENT = "textbaseClient";

    // Host and port only ever travel together to address one service, so
    // VECTORSTORE_URL/EMBEDDER_URL are each a single "host:port" pass-store
    // secret rather than two - trivial to split back apart here. The pass
    // store's own values are "http://host:port" (a real URL, readable on
    // its own), so a scheme prefix is stripped first if present rather
    // than requiring every caller (compose, Rakefile-loaded env) to do it.
    private static String stripScheme(String address) {
        final int idx = address.indexOf("://");
        return idx >= 0 ? address.substring(idx + 3) : address;
    }
    private static String hostOf(String address) { final String a = stripScheme(address); return a.substring(0, a.lastIndexOf(':')); }
    private static int portOf(String address) { final String a = stripScheme(address); return Integer.parseInt(a.substring(a.lastIndexOf(':') + 1)); }

    /**
     * The effective collection name: the environment prefix (dev-/int-,
     * so non-prod stages share the one prod store without ever touching
     * prod data) + the convention-carrying base name. Same naming for
     * every store - a name identifies "this corpus embedded with this
     * encoder", never "this store" (see MilvusTextSearchService's
     * compatibility check, which the prefix never disturbs).
     */
    static String prefixedCollectionName(String prefix, String baseName) {
        return (prefix == null ? "" : prefix) + baseName;
    }

    @Bean
    @ConditionalOnProperty(name = "vector.store", havingValue = "milvus")
    public MilvusServiceClient milvusClient(@Value("${vectorstore.address}") String vectorStoreAddress) {
        final String milvusHost = hostOf(vectorStoreAddress);
        final int milvusPort = portOf(vectorStoreAddress);
        log.info(String.format("MilvusServiceClient: %s:%d", milvusHost, milvusPort));
        return new MilvusServiceClient(ConnectParam.newBuilder()
            .withHost(milvusHost)
            .withPort(milvusPort)
            // Do not let an unavailable development Milvus block forever;
            // the experiment/test caller can make at most its own bounded
            // retry rather than inheriting an unbounded gRPC wait.
            .withConnectTimeout(2, TimeUnit.SECONDS)
            .withRpcDeadline(3, TimeUnit.SECONDS)
            .build()
        );
    }

    final static String ALL_MINILM_L6_V2 = "all-MiniLM-L6-v2";
    final static String ALL_MPNET_BASE_V2 = "all-mpnet-base-v2";

    // The STS-backed models remain available by qualifier. BGE-M3 below is
    // the primary because semantic queries must use the same encoder as the
    // vectors written by textbase-nestjs.

    @Bean
    public StsEmbedder model_prod_all_MiniLM_L6_v2(
            @Value("${sts.host}") String stsHost,
            @Value("${sts.port}") int stsPort,
            RestTemplate restTemplate
    ) {
        final StsEmbedder embedder = new StsEmbedder(stsHost, stsPort, ALL_MINILM_L6_V2, MilvusCollection.DIM_384, restTemplate);
        log.info(embedder.toString());
        return embedder;
    }


    @Bean
    public StsEmbedder model_all_mpnet_base_v2 (
            @Value("${sts.host}") String stsHost,
            @Value("${sts.port}") int stsPort,
            RestTemplate restTemplate
    ) {
        return new StsEmbedder(stsHost, stsPort, ALL_MPNET_BASE_V2, MilvusCollection.DIM_768, restTemplate);
    }

    // Ollama-backed embedders - both served by the same Ollama instance
    // (ollama.host:ollama.port), any pulled model addressable just by name.
    // All stay reachable by their own bean names for callers/tests built
    // against them specifically.

    @Bean
    @Primary
    public Embedder bgeM3Embedder(
            @Value("${embedder.address}") String embedderAddress,
            @Qualifier("ollamaRestTemplate") RestTemplate ollamaRestTemplate,
            OllamaHealthTracker ollamaHealthTracker
    ) {
        final Embedder embedder = new OllamaEmbedder(hostOf(embedderAddress), portOf(embedderAddress), "bge-m3", "BGE_M3", MilvusCollection.DIM_1024, ollamaRestTemplate, ollamaHealthTracker);
        log.info(embedder.toString());
        return embedder;
    }

    @Bean
    public Embedder qwen3EmbeddingEmbedder(
            @Value("${embedder.address}") String embedderAddress,
            @Qualifier("ollamaRestTemplate") RestTemplate ollamaRestTemplate,
            OllamaHealthTracker ollamaHealthTracker
    ) {
        final Embedder embedder = new OllamaEmbedder(hostOf(embedderAddress), portOf(embedderAddress), "qwen3-embedding:4b", "QWEN3_EMBEDDING_4B", MilvusCollection.DIM_2560, ollamaRestTemplate, ollamaHealthTracker);
        log.info(embedder.toString());
        return embedder;
    }

    @Bean
    public Embedder nomicEmbedder(
            @Value("${embedder.address}") String embedderAddress,
            @Qualifier("ollamaRestTemplate") RestTemplate ollamaRestTemplate,
            OllamaHealthTracker ollamaHealthTracker
    ) {
        final Embedder embedder = new OllamaEmbedder(hostOf(embedderAddress), portOf(embedderAddress), "nomic-embed-text:v1.5", "NOMIC_EMBED_TEXT", MilvusCollection.DIM_768, ollamaRestTemplate, ollamaHealthTracker);
        log.info(embedder.toString());
        return embedder;
    }

    // The vector store behind VectorCollection-backed search: qdrant
    // (the default - the shared prod instance serves every environment,
    // with per-environment collection names) or milvus (the historic
    // store, still fully supported). Selected once at wiring time - the
    // whole rest of the vector layer depends on VectorCollection, never
    // on which implementation is behind it.
    @Bean
    @ConditionalOnProperty(name = "vector.store", havingValue = "milvus")
    MilvusCollection prodCollection(MilvusService milvusService,
            // biblioteca-nestjs never has a collection name of its own -
            // it takes this one from GET /api/admin/config.
            @Value("${vector.collection:biblioteca_paragraphs_bge_m3}") String collectionBaseName,
            @Value("${vector.collection.prefix:}") String collectionPrefix) {

        final MilvusCollection col = new MilvusCollection(milvusService, prefixedCollectionName(collectionPrefix, collectionBaseName)) {
            @Override
            public void create(int vectorDimension) {
                throw new IllegalStateException("create disabled for production read-only collection " + name);
            }
        };
        log.info(col.toString());
        return col;
    }

    /**
     * The qdrant counterpart of prodCollection above - same
     * convention-carrying collection name (see VectorTextSearchService's
     * model-name compatibility check), different store behind the same
     * VectorCollection contract.
     */
    @Bean
    @ConditionalOnProperty(name = "vector.store", havingValue = "qdrant", matchIfMissing = true)
    QdrantCollection qdrantProdCollection(
            @Value("${vectorstore.address}") String vectorStoreAddress,
            @Value("${vector.collection:biblioteca_paragraphs_bge_m3}") String collectionBaseName,
            @Value("${vector.collection.prefix:}") String collectionPrefix,
            @Qualifier("qdrantRestTemplate") RestTemplate qdrantRestTemplate) {
        final QdrantCollection col = new QdrantCollection(vectorStoreAddress,
                prefixedCollectionName(collectionPrefix, collectionBaseName), qdrantRestTemplate);
        log.info(col.toString());
        return col;
    }

    /**
     * Bounded like ollamaRestTemplate below: a reachable-but-unresponsive
     * Qdrant must fail fast (empty results / availability flip) instead of
     * hanging a search request or the periodic availability check.
     */
    @Bean
    public RestTemplate qdrantRestTemplate() {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        return new RestTemplate(factory);
    }

    @Bean(TEXTBASE_CLIENT)
    public TextbaseClient textbaseClient(@Value("${textbase.advertised.url:https://textbase.scriptorium.ro}") String baseUrl, RestTemplate restTemplate) {
        return new TextbaseClient(baseUrl, restTemplate);
    }

    @Bean
    public UrlContentResolver urlContentResolver(@Lazy RestTemplate restTemplate) {
        return new UrlContentResolver(restTemplate);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // The plain restTemplate() above has NO connect/read timeout at all
    // (SimpleClientHttpRequestFactory defaults to "wait forever") - fine
    // for most callers, but a real problem for Ollama specifically: a
    // reachable-but-GPU-contended instance can otherwise block a request
    // thread indefinitely rather than ever throwing, which means neither
    // Util.runWithTimeout's own bound (VectorTextSearchService,
    // SearchRestController) NOR OllamaHealthTracker's markUnavailable()
    // ever actually fires - the blocked thread just never gets back to
    // either. A bounded read timeout here is what makes both of those
    // mechanisms actually work for a genuine hang, not just a fast
    // connection-refused. 30s is generous for a real (non-hung) generate
    // call under normal load, per this session's own observed latencies.
    @Bean
    public RestTemplate ollamaRestTemplate() {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        return new RestTemplate(factory);
    }

}
