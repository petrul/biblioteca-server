package ro.editii.scriptorium.vector

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.search.VectorHit

import static ro.editii.scriptorium.GTestUtil.p

@SpringBootTest(
        classes = [
            VectorConfig.class,
            MilvusService.class,
            VectorSearchAvailability.class,
            VectorTextSearchService.class,
            ro.editii.scriptorium.health.OllamaHealthTracker.class,
            // must come AFTER VectorConfig.class - classes= entries are
            // processed in order, and a later definition for the same bean
            // name wins (allow-bean-definition-overriding=true below).
            // Putting this in an @Import instead does NOT work: imports on
            // the test class get processed before the primary classes=
            // list, so VectorConfig's real bean would win instead.
            FakeEmbedderTestConfig.class],
        properties = [
        // This is the milvus-backend itest: qdrant is the default store now,
        // so the milvus beans (MilvusService included) must be opted into.
        "vector.store=milvus",
        "vectorstore.address=http://srv2.local:20112",
        // This class owns the full lifecycle of its own random-named
        // collection on the dedicated integration milvus - the per-env
        // prefix (dev-, for sharing the prod qdrant) must not apply to it.
        "vector.collection.prefix=",
        "embeddings.host=mini.local",
        "embeddings.port=11200",
        "embedder.address=http://zmeu.local:11434",
        "textbase-dl.dir=~/data/textbase-dl",
        "spring.main.allow-bean-definition-overriding=true"
])
@Import(TestConfig.class)
@Tag("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MilvusServiceTest {

    /**
     * Unique per run: concurrent test executions (two developers, or a
     * developer plus a CI agent) share the same Milvus instance
     * (srv2.local:20112), and this class drops the existing collection of
     * this name before creating a fresh one - with a fixed name, one run's
     * setup silently drops the other run's collection mid-test. Registered
     * as the context's vector.collection via @DynamicPropertySource below,
     * so the app's MilvusCollection bean and this test's setup/teardown
     * agree on it.
     */
    static final String TEST_COLLECTION = "test_tb_paras_qwen3_embedding_4b_" + TestUtils.randomString()

    @DynamicPropertySource
    static void milvusCollection(DynamicPropertyRegistry registry) {
        registry.add("vector.collection", { TEST_COLLECTION })
    }

    @Autowired MilvusService milvusService
    @Autowired MilvusCollection milvusCollection
    @Autowired Embedder embedder
    @Autowired VectorTextSearchService milvusTextSearchService
    @Autowired VectorSearchAvailability vectorSearchAvailability

    @BeforeAll
    void setupMilvusCollection() {
        // milvusCollection is the guarded "prodCollection" bean (create() disabled as a safety
        // measure); go through MilvusService directly to get an unguarded handle for setup/teardown.
        final MilvusCollection col = this.milvusService.getAt(TEST_COLLECTION)
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

        // VectorSearchAvailability already ran its one-shot check at
        // ApplicationReadyEvent, before this collection existed - re-run it
        // now that it does, or milvusTextSearchService.search() would keep
        // returning empty results for the rest of this test class (a real
        // deployment would need a restart to pick this up; here we can just
        // ask it to check again).
        this.vectorSearchAvailability.checkAvailability()
    }

    private static void waitUntilRowsAreVisible(MilvusCollection col, int expectedRows) {
        final deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() <= deadline) {
            final stats = col.statistics.data.statsList
            final rowCount = stats.find { it.key == 'row_count' }
            if (rowCount != null && rowCount.value.toInteger() == expectedRows)
                return
            Thread.sleep(250)
        }
        assert col.statistics.data.statsList.find { it.key == 'row_count' }?.value?.toInteger() == expectedRows
    }

    @AfterAll
    void teardownMilvusCollection() {
        this.milvusService.getAt(TEST_COLLECTION).drop()
    }

    @Test
    void searchOnMilvusService() {

        final String[] sentences = ['foaie verde', 'we go to London every day if we want']

        final vectors = this.embedder.encode(sentences)
        vectors.each {assert it.length == MilvusCollection.DIM_2560 }

        p this.milvusCollection.search(vectors)
        p this.milvusCollection.statistics
        p this.milvusCollection.info

        final resp = this.milvusCollection.search(vectors[0])
        assert resp.getIDScore(0).size() > 0
        final resp2 = this.milvusCollection.search(vectors[1])
        assert resp2.getIDScore(0).size() > 0
    }

    @Test
    void searchOnMilvusTextService() {
        final String[] sentences = ['foaie verde',
                                    'we go to London every day if we want',
                                    "qu'en penses tu?"]

        final vectors = this.embedder.encode(sentences)
        final v1 = vectors[0] // romanian
        final v2 = vectors[1] // english
        final v3 = vectors[2] // french

        assert v1.length == v2.length
        assert v1 != v2
        assert v2 != v3

        p "v1: $v1"
        p "v2: $v2"
        p "v3: $v3"
        assert v1.length == MilvusCollection.DIM_2560

        final r1 = this.milvusTextSearchService.search(v1)
        final r2 = this.milvusTextSearchService.search(v2)
        final r3 = this.milvusTextSearchService.search(v3)

        printHits r1
        printHits r2
        printHits r3

//        this.milvusTextSearchService.search(sentences[0]).subList(0,4).each {assert it.content.toLowerCase().contains('foaie')}
//        this.milvusTextSearchService.search(sentences[1]).subList(0,4).each {assert it.content.toLowerCase().contains('london')}
//        this.milvusTextSearchService.search(sentences[2]).subList(0,4).each {assert it.content.toLowerCase().contains('penses')}

        sentences.each {s ->

//            p ">>>>> q: $s"

            final resp = this.milvusTextSearchService.search(s)
            assert resp.size() > 0

            resp.forEach {hit ->
                p("${hit.content} - ${hit.score} - ${hit.url}")
            }

        }

    }

    float[] randomFloatVect(int dim) {
        float[] resp =new float[dim]
        (0..dim - 1).each { resp[it] = new Random().nextFloat() }
        return resp
    }

    void printHits(List<VectorHit> milvusHits) {
        p(("> Hits:"))
        milvusHits.each {p "\t$it"}
    }

}
