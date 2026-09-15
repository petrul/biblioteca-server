package ro.editii.scriptorium.vector;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import ro.editii.scriptorium.client.TextbaseClient;
import ro.editii.scriptorium.health.OllamaHealthTracker;
import ro.editii.scriptorium.search.content.UrlContentResolver;

@Configuration
@Log4j2
public class VectorConfig {

    public static final String TEXTBASE_CLIENT = "textbaseClient";

    // Host and port only ever travel together to address one service, so
    // MILVUS_ADDRESS/EMBEDDER_ADDRESS are each a single "host:port" Vault
    // secret rather than two - trivial to split back apart here.
    private static String hostOf(String address) { return address.substring(0, address.lastIndexOf(':')); }
    private static int portOf(String address) { return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)); }

    @Bean
    public MilvusServiceClient milvusClient(@Value("${milvus.address}") String milvusAddress) {
        final String milvusHost = hostOf(milvusAddress);
        final int milvusPort = portOf(milvusAddress);
        log.info(String.format("MilvusServiceClient: %s:%d", milvusHost, milvusPort));
        return new MilvusServiceClient(ConnectParam.newBuilder()
            .withHost(milvusHost)
            .withPort(milvusPort)
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

    @Bean
    MilvusCollection prodCollection(MilvusService milvusService,
            // This exact name is also configured in textbase-nestjs .env.dev.
            @Value("${milvus.collection:int_tb_paras_bge_m3}") String collectionName) {

        final MilvusCollection col = new MilvusCollection(milvusService, collectionName) {
            @Override
            public void create(int vectorDimension) {
                throw new IllegalStateException("create disabled for production read-only collection " + name);
            }
        };
        log.info(col.toString());
        return col;
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
    // Util.runWithTimeout's own bound (MilvusTextSearchService,
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
