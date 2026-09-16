package ro.editii.scriptorium;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import ro.editii.scriptorium.cache.CacheConf;
import ro.editii.scriptorium.cache.DiskCache;
import ro.editii.scriptorium.client.TextbaseClient;
import ro.editii.scriptorium.dto.OpusRemovedDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.dto.UserLoggedInDto;
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher;
import ro.editii.scriptorium.tei.TeiDirRepoImpl;
import ro.editii.scriptorium.tei.TeiRepo;
import ro.editii.scriptorium.vector.VectorConfig;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@TestConfiguration
@Import(AppConfig.class)
@Log4j2
public class TestConfig {

    public static final String REST_TEMPLATE_NO_REDIRECT = "restTemplateNoRedirect";

    @Bean
    public TeiRepo teiRepo() {
        final String dirname = Util.urlToFileString(this.getClass().getClassLoader().getResource("testrepo"));
        return new TeiDirRepoImpl(dirname);
    }

    @Bean
    public RestTemplateBuilder restTemplateBuilder() {
        return new RestTemplateBuilder();
    }

    @Bean(REST_TEMPLATE_NO_REDIRECT) @Primary
    public RestTemplate restTemplate(RestTemplateBuilder restTemplateBuilder) {
        final SimpleClientHttpRequestFactory noRedirectFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };

        final RestTemplate resp = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(100 * 60))
                .readTimeout(Duration.ofSeconds(100 * 60))
                .requestFactory(() -> noRedirectFactory)
                .messageConverters(List.of(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new ByteArrayHttpMessageConverter(),
                        new GsonHttpMessageConverter()
                ))
                .build();
        return resp;
    }

    @Primary @Bean(name = CacheConf.CACHE_TOC, destroyMethod = "delete")
    public DiskCache cacheToc() {
        final String tmpdir = System.getProperty("java.io.tmpdir");
        final File cacheDir = new File(tmpdir, "TEST-TOC" + TestUtils.randomString());
        return new DiskCache(cacheDir);
    }

    @Primary @Bean(name = CacheConf.CACHE_NODE, destroyMethod = "delete")
    public DiskCache cacheNode() {
        final String tmpdir = System.getProperty("java.io.tmpdir");
        final File cacheDir = new File(tmpdir, "TEST-NODE" + TestUtils.randomString());
        return new DiskCache(cacheDir);
    }

    @Primary @Bean(name = CacheConf.CACHE_BINARY_OBJECT, destroyMethod = "delete")
    public DiskCache cacheBinaryObj() {
        final String tmpdir = Util.getTmpDir();
        final File cacheDir = new File(tmpdir, "TEST-BINOBJ" + TestUtils.randomString());
        return new DiskCache(cacheDir);
    }

    @Bean(VectorConfig.TEXTBASE_CLIENT) @Lazy @Primary
    TextbaseClient textbaseClient(@LocalServerPort int port,
                                  @Qualifier(REST_TEMPLATE_NO_REDIRECT) RestTemplate restTemplateNoRedirect) {
        final String baseUrl = String.format("http://localhost:%d", port);
        final RestTemplate rt = restTemplateNoRedirect;
        return new TextbaseClient(baseUrl, rt);
    }

    @Bean @Primary
    public TextbaseEventsPublisher textbaseEventsPublisher() {
        return new TestEventPublish();
    }

    static class TestEventPublish implements TextbaseEventsPublisher {

        final List<TeiDivDto> newOpus = new ArrayList<>();
        final List<TeiDivDto> reimportedOpus = new ArrayList<>();
        final List<OpusRemovedDto> removedOpus = new ArrayList<>();
        final List<UserLoggedInDto> logins = new ArrayList<>();

        @Override
        public void signalNewOpusImported(TeiDivDto div) {
            this.newOpus.add(div);
        }

        @Override
        public void signalOpusReimported(TeiDivDto div) {
            this.reimportedOpus.add(div);
        }

        @Override
        public void signalOpusRemoved(OpusRemovedDto event) {
            this.removedOpus.add(event);
        }

        @Override
        public void signalUserLoggedIn(UserLoggedInDto event) {
            this.logins.add(event);
        }
    }
}

