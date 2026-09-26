package ro.editii.scriptorium.web


import org.apache.commons.io.FileUtils
import org.apache.commons.io.output.NullWriter
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Lazy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.BibliotecaServer
import ro.editii.scriptorium.client.TextbaseClient
import ro.editii.scriptorium.dto.HitDto
import ro.editii.scriptorium.service.AdminService
import ro.editii.scriptorium.vector.Content
import ro.editii.scriptorium.vector.FakeEmbedderTestConfig
import ro.editii.scriptorium.vector.MilvusCollection
import ro.editii.scriptorium.vector.MilvusService

import java.nio.file.Files

import static ro.editii.scriptorium.GTestUtil.p
import static ro.editii.scriptorium.TestUtils.TEI_ELEM

@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:myDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "vector.store=milvus",
        "vectorstore.address=http://srv2.local:20112",
        // The collection below is this test's own random-named one on the
        // dedicated integration milvus - the per-env prefix (dev-, meant
        // for sharing the prod qdrant) must not apply to it.
        "vector.collection.prefix=",
        "embeddings.host=mini.local",
        "embeddings.port=11200",
        "embedder.address=http://zmeu.local:11434",
        "textbase.advertised.url=http://localhost:8080"
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [BibliotecaServer.class, TestConfig.class, FakeEmbedderTestConfig.class])
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
@Tag("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchITest {

    /**
     * Unique per run: concurrent itest executions (two developers, or a
     * developer plus a CI agent) share the same Milvus instance this test
     * uses (srv2.local:20112), and setupMilvusCollection drops the existing
     * collection of this name before creating a fresh one - with a fixed
     * name, one run's setup silently drops the other run's collection
     * mid-test (ann()/search() then fail with "can't find collection").
     * Registered as the context's vector.collection via @DynamicPropertySource
     * below, so the app's vector store and this test's setup/teardown agree.
     */
    static final String TEST_MILVUS_COLLECTION = "test_tb_paras_qwen3_embedding_4b_" + TestUtils.randomString()

    @DynamicPropertySource
    static void milvusCollection(DynamicPropertyRegistry registry) {
        registry.add("vector.collection", { TEST_MILVUS_COLLECTION })
    }

    @Autowired @Lazy TextbaseClient tbc;
    @Autowired AdminService adminService
    @Autowired MilvusService milvusService
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired ro.editii.scriptorium.vector.VectorSearchAvailability vectorSearchAvailability

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

        final  hits_again = this.tbc.get_api_search_ann(elem.id).data.hits

        // Same set of nearest neighbours, not necessarily in the same order:
        // each call recomputes the query embedding fresh (this div's random
        // test vector never matches on sha256), and Qwen3-Embedding-4B via
        // Ollama has tiny call-to-call numeric jitter (GPU non-determinism) -
        // unlike the sentence-transformers embedder this replaced, which
        // didn't exhibit that at this precision. With only 12 random test
        // vectors in the collection, several are near-tied, so that jitter
        // can be enough to swap the rank of two near-tied candidates even
        // though the actual nearest-neighbour set stays the same.
        assert hits.length == hits_again.length
        assert (hits*.url as Set) == (hits_again*.url as Set)

    }

    @Test
    void search() {
        _1:{
            final resp = this.tbc.get_api_search_milvus("moldov")
            final divs = resp.findAll {it -> it.type == HitDto.TYPES.div.name()}
            final milv = resp.findAll {it -> it.type == HitDto.TYPES.milvus.name()}
            assert divs.size() == 0
            assert milv.size() > 0

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
        assert countTableRows("tei_file_authors") > 0
        assert countTableRows(TEI_ELEM) > 0

    }

    @AfterAll
    void afterTransaction() {
        this.truncateAllTables();
    }

    @BeforeAll
    void setupMilvusCollection() {
        // the app's own MilvusCollection bean is the guarded "prodCollection" (create() disabled
        // as a safety measure); go through MilvusService directly to get an unguarded handle for setup/teardown.
        final MilvusCollection col = this.milvusService.getAt(TEST_MILVUS_COLLECTION)
        if (col.exists()) {
            col.drop()
        }
        final dim = MilvusCollection.DIM_2560
        col.create(dim)
        col.createIndexIvfSq8()

        final rnd = new Random()
        final nrRows = 12
        final Content[] content = (1..nrRows).collect {
            new Content(
                    TestUtils.randomString(5),
                    TestUtils.randomString(10),
                    (0..<dim).collect { rnd.nextFloat() } as float[]
            )
        }
        final insertResult = col.insert(content)
        assert insertResult.data.insertCnt == nrRows
        col.flush()
        waitUntilRowsAreVisible(col, nrRows)
        col.load()

        // VectorSearchAvailability's one-shot startup check ran before this
        // collection existed, so it snapshotted "unavailable" - re-check now
        // that it does, or every search()/ann() call below would just get
        // empty results for the rest of this test class (see MilvusServiceTest).
        this.vectorSearchAvailability.checkAvailability()
    }

    private static void waitUntilRowsAreVisible(MilvusCollection col, int expectedRows) {
        final deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() <= deadline) {
            // A failed/empty R (data null - Milvus briefly unavailable or the
            // collection not yet queryable after create/flush) is exactly
            // what this loop exists to wait out; dereferencing it directly
            // NPEs and fails the whole class from @BeforeAll instead.
            final rowCount = col.statistics?.data?.statsList?.find { it.key == 'row_count' }
            if (rowCount != null && rowCount.value.toInteger() == expectedRows)
                return
            Thread.sleep(250)
        }
        assert col.statistics?.data?.statsList?.find { it.key == 'row_count' }?.value?.toInteger() == expectedRows
    }

    @AfterAll
    void teardownMilvusCollection() {
        this.milvusService.getAt(TEST_MILVUS_COLLECTION).drop()
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
        assert countTableRows("tei_file_authors") > 0
        assert countTableRows(TEI_ELEM) > 0
    }

    @AfterEach
    void afterEach() {
        FileUtils.deleteDirectory(this.cacheDir)
        p "deleted basedir ${this.cacheDir}"
    }
}
