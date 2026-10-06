package ro.editii.scriptorium.web


import org.apache.commons.io.FileUtils
import org.apache.commons.io.output.NullWriter
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Lazy
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestTemplate
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.BibliotecaServer
import ro.editii.scriptorium.client.BibliotecaClient
import ro.editii.scriptorium.dto.HitDto
import ro.editii.scriptorium.service.AdminService
import ro.editii.scriptorium.vector.Content
import ro.editii.scriptorium.vector.FakeQwen3Embedder
import ro.editii.scriptorium.vector.QdrantCollection

import java.nio.file.Files

import static ro.editii.scriptorium.GTestUtil.p
import static ro.editii.scriptorium.TestUtils.TEI_ELEM

/**
 * The web-layer vector-search itest, migrated from the retired milvus to
 * qdrant - the default store (see VectorConfig's conditional beans): the
 * full app boots (in-memory Derby, the fixture corpus reimported), a
 * random-named collection is created on the shared qdrant instance behind
 * the app's own VectorCollection bean, and /api/search/ann plus
 * /api/search/vector go through the real search path end to end - the
 * FakeQwen3Embedder keeps the query vectors deterministic and offline,
 * so only the STORE is a live integration dependency.
 *
 * Fixture hit urls point at this test server's own /util/echo endpoint:
 * UrlContentResolver fetches `<url>.txt` and drops hits whose content no
 * longer resolves (VectorUtils.searchHitsToHits), so the fixture must use
 * urls that answer - /util/echo replies to any query string, which is all
 * the "is this hit still alive" resolution needs.
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:derby:memory:myDb;create=true",
        "spring.datasource.driver-class-name=org.apache.derby.iapi.jdbc.AutoloadedDriver",
        "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        // The store under test: qdrant is the default (matchIfMissing), the
        // address comes from the profile env (VECTORSTORE_URL) - registered
        // suffix-stripped in qdrantCollection's @DynamicPropertySource below,
        // because a URL-carried collection would win over this test's own
        // vector.collection.
        "vector.collection.prefix=",
        "embeddings.host=mini.local",
        "embeddings.port=11200",
        "embedder.address=http://zmeu.local:11434",
        "textbase.advertised.url=http://localhost:8080"
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [BibliotecaServer.class, TestConfig.class, ro.editii.scriptorium.vector.FakeEmbedderTestConfig.class])
@EnableAutoConfiguration(exclude= KafkaAutoConfiguration.class)
@Tag("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchITest {


    /**
     * Unique per run: concurrent itest executions (two developers, or a
     * developer plus a CI agent) share the same qdrant instance, and setup
     * drops the existing collection of this name before creating a fresh
     * one - with a fixed name, one run's setup silently drops the other
     * run's collection mid-test. Registered as the context's
     * vector.collection via @DynamicPropertySource below, so the app's
     * QdrantCollection bean and this test's setup/teardown agree on it.
     * The int-test_ prefix marks it as recognizable-and-deletable test
     * residue (see QdrantCollectionITest's own naming note), and the name
     * carries the fake embedder's model - VectorTextSearchService's
     * model/collection compatibility check requires it.
     */
    static final String TEST_COLLECTION =
            "int-test_qdrant_searchitest_qwen3_embedding_4b_" + TestUtils.randomString()

    @DynamicPropertySource
    static void qdrantCollection(DynamicPropertyRegistry registry) {
        // VECTORSTORE_URL may carry its own collection as the final path
        // segment (see VectorConfig.qdrantProdCollection) - and that suffix
        // WINS over vector.collection, which would point the app bean (and
        // VectorTextSearchService's model/collection compatibility check)
        // at the profile's stage collection instead of this run's
        // random-named one. The stripped value must ALSO be registered
        // under the raw VECTORSTORE_URL key: RuntimeConfigService installs
        // its map as the FIRST property source and derives
        // vectorstore.address from environment.getProperty("VECTORSTORE_URL"),
        // so it would outrank a plain vectorstore.address override and
        // feed the suffixed URL back to the bean.
        final String rawAddress = System.getenv('VECTORSTORE_URL') ?: 'http://srv2.local:20126'
        final String baseAddress = TestUtils.vectorStoreBaseAddress(rawAddress)
        registry.add("VECTORSTORE_URL", { baseAddress })
        registry.add("vectorstore.address", { baseAddress })
        registry.add("vector.collection", { TEST_COLLECTION })
    }

    @Autowired @Lazy BibliotecaClient tbc;
    @Autowired AdminService adminService
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired ro.editii.scriptorium.vector.VectorSearchAvailability vectorSearchAvailability
    @org.springframework.boot.test.web.server.LocalServerPort int port

    /** The 12 fixture points' urls: this server's own echo endpoint. */
    private String fixtureUrl(int i) {
        return "http://localhost:${this.port}/util/echo?msg=qdrant-itest-hit-${i}"
    }

    /**
     * approximate nearest neighbours
     */
    @Test
    void ann() {
        def creangaPovesti = "/creanga/povesti"

        final resp = this.tbc.get_api_search_ann(creangaPovesti)
        final hits = resp.data.hits
        assert hits.length > 0

        final elem = this.tbc.get_api_drest_teiDivs_byPath(creangaPovesti)
        elem.with { elemdto ->
            assert elemdto != null
            assert elemdto.id > 0
            assert !elemdto.path.empty
            assert !elemdto.urlFragment.empty
            assert !elemdto.url.empty
        }

        final hits_again = this.tbc.get_api_search_ann(elem.id).data.hits

        // Same set of nearest neighbours: the fake embedder is deterministic
        // (seeded from the text's hashCode), so both calls embed the div to
        // the exact same query vector and rank the 12 fixture points
        // identically - unlike the real GPU embedder this suite ran with in
        // its milvus days, whose call-to-call jitter could swap near-ties.
        assert hits.length == hits_again.length
        assert (hits*.url as Set) == (hits_again*.url as Set)
    }

    @Test
    void search() {
        _1: {
            final resp = this.tbc.get_api_search_vector("moldov")
            final divs = resp.findAll {it -> it.type == HitDto.TYPES.div.name()}
            final vect = resp.findAll {it -> it.type == HitDto.TYPES.milvus.name()}
            assert divs.size() == 0
            // the vector path is live against the real store: every one of
            // the 12 fixture points survives the url resolution
            assert vect.size() > 0

            divs.each {
                final dto = (ro.editii.scriptorium.dto.TeiDivDto) it.data
                assert dto.head.toLowerCase().contains('moldov')
            }
        }

        _2: {
            final resp = this.tbc.get_api_search_authors("alec")
            final authors = resp.findAll {it -> it.type == HitDto.TYPES.author.name()}
            assert authors.size() > 0

            p resp
            p "==="
            p authors
        }

    }

    void truncateAllTables() {
        TestUtils.truncateAllTables(this.jdbcTemplate)
    }


    @BeforeAll
    void beforeAll() {
        this.truncateAllTables()

        adminService.reimportAllTeis(new OutputStreamWriter(System.out))

        assert countTableRows("author") > 0
        assert countTableRows(TestUtils.TEI_FILE_AUTHORS) > 0
        assert countTableRows(TEI_ELEM) > 0
    }

    @AfterAll
    void afterAll() {
        this.truncateAllTables();
    }

    /**
     * The qdrant counterpart of the retired milvus setup: an unguarded
     * handle on the same random-named collection the app bean points at,
     * with the lifecycle-tolerant RestTemplate (collection create/drop are
     * slow while the shared store is under bulk-vectorization load - see
     * QdrantCollectionITest's own timeout note). An unavailable or
     * misconfigured store fails this test; the endpoint and full exception
     * are printed so CI does not hide an infrastructure problem.
     *
     * The whole setup is guarded so a failure at any step (create landed
     * but an index step failed, upsert rejected, availability check blew
     * up) still drops the collection before rethrowing - @AfterAll would
     * get it too, but a half-done setup must not rely on that.
     */
    @BeforeAll
    void setupQdrantCollection() {
        final col = this.connect()
        try {
            // Every operation, including the initial existence probe, is a
            // network operation.  Log the exact sanitized endpoint and the
            // complete exception before failing: an unavailable Qdrant in CI
            // is a configuration/infrastructure failure, not a reason to
            // silently skip this integration test.
            if (col.exists()) col.drop()
            col.create(FakeQwen3Embedder.DIM, "qdrant search itest fixture - safe to delete")

            final rnd = new Random()
            final nrRows = 12
            final Content[] content = (1..nrRows).collect { i ->
                // A hex sha256 like the real pipeline stores: QdrantCollection
                // derives the point ID by dashing the sha's first 32 chars, so
                // it must be hex (a real sha256 always is).
                Content.builder()
                        .sha256(String.format('%064x', i))
                        .url(fixtureUrl(i))
                        .embedding((0..<FakeQwen3Embedder.DIM).collect { rnd.nextFloat() } as float[])
                        .build()
            }
            col.upsert(content)

            // VectorSearchAvailability's one-shot startup check ran before this
            // collection existed, so it snapshotted "unavailable" - re-check now
            // that it does, or every search()/ann() call below would just get
            // empty results for the rest of this test class.
            this.vectorSearchAvailability.checkAvailability()
        } catch (Throwable setupFailure) {
            final endpoint = TestUtils.vectorStoreBaseAddress(System.getenv('VECTORSTORE_URL') ?: 'http://srv2.local:20126')
            System.err.println("SearchITest: Qdrant unavailable or setup failed at ${endpoint}; full exception follows")
            setupFailure.printStackTrace(System.err)
            this.dropBestEffort(col, setupFailure)
            throw setupFailure
        }
    }

    private QdrantCollection connect() {
        // Suffix-stripped like vectorstore.address above: this handle must
        // talk to the same base address the app bean does, not to a URL
        // whose trailing collection segment would end up in every REST
        // path as garbage.
        final address = TestUtils.vectorStoreBaseAddress(System.getenv('VECTORSTORE_URL') ?: 'http://srv2.local:20126')
        final factory = new SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(2_000)
        factory.setReadTimeout(60_000)
        return new QdrantCollection(address, TEST_COLLECTION, new RestTemplate(factory))
    }

    /**
     * All-quiet on teardown: the run's verdict is already determined and
     * an exception here would only obscure it. A failed drop leaves a
     * recognizable int-test_-prefixed collection (see the class comment)
     * and says so loudly.
     */
    @AfterAll
    void teardownQdrantCollection() {
        final col = this.connect()
        this.dropBestEffort(col, null)
    }

    /**
     * Mirror of QdrantCollectionITest's dropBestEffort: the collection
     * must never outlive the run, but a cleanup failure may only fail the
     * run when nothing had failed before it - on setup failures and test
     * failures it is logged, never rethrown.
     */
    private void dropBestEffort(QdrantCollection col, Throwable primary) {
        try {
            if (col.exists()) col.drop()
            assert !col.exists()
        } catch (Throwable cleanup) {
            if (primary != null) {
                System.err.println("SearchITest: dropping ${TEST_COLLECTION} failed after a prior failure (${primary}) - the collection may need a manual int-test_ prefix sweep: ${cleanup.message}")
                return
            }
            // Teardown of a green run: a failed drop still fails the run -
            // silently swallowing it here is exactly how the 117 leaked
            // collections accumulated.
            throw cleanup
        }
    }

    def countTableRows(tableName) {
        return this.jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class)
    }

    protected reimportTeis() {
        adminService.reimportAllTeis(new NullWriter())
    }

    File cacheDir

    @BeforeEach
    void beforeEach() {
        final String tmpdir = TestUtils.tmpDir
        this.cacheDir = new File(tmpdir, "SearchItest-" + TestUtils.randomString())
        Files.createDirectories(this.cacheDir.toPath())
        p "created basedir ${this.cacheDir}"

        this.reimportEverything()
    }

    void reimportEverything() {
        this.truncateAllTables()
        reimportTeis()

        assert countTableRows("author") > 0
        assert countTableRows(TestUtils.TEI_FILE_AUTHORS) > 0
        assert countTableRows(TEI_ELEM) > 0
    }

    @AfterEach
    void afterEach() {
        FileUtils.deleteDirectory(this.cacheDir)
        p "deleted basedir ${this.cacheDir}"
    }
}
