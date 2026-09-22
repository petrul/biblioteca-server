package ro.editii.scriptorium.vector


import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils

import static ro.editii.scriptorium.GTestUtil.p

@SpringBootTest(
        classes = [ VectorConfig.class, MilvusService.class, VectorSearchAvailability.class, MilvusTextSearchService.class, ro.editii.scriptorium.health.OllamaHealthTracker.class],
        properties = [
                "milvus.url=http://srv2.local:20112",
                "embedder.url=http://zmeu.local:11434",
                "spring.main.allow-bean-definition-overriding=true"
        ])
@Import(TestConfig.class)
@Tag("integration-test")
@Timeout(45L)
class MilvusServiceExperimentTest {

    @Autowired MilvusService milvusService

    @Test
    void collectionExists() {
        final name = 'cannotexist_' + TestUtils.randomString(20)
        final MilvusCollection col = this.milvusService[name]
        try {
            assert ! col.exists()
            col.create(200)
            assert col.exists()
        } finally {
            col.drop()
            assert !col.exists()
        }
    }

    @Test
    void createAndDeleteVectors() {
        final dim = MilvusCollection.DIM_768
        final colname = "test_" + TestUtils.randomString(10)

        final MilvusCollection col = this.milvusService[colname]
        col.create(dim)

        try {
            final rnd = new Random()

            final nrRows = 12;
            final Content[] content = (1..nrRows).collect {
                new Content(
                        TestUtils.randomString(5),
                        TestUtils.randomString(10),
                        (0..<dim).collect { rnd.nextFloat() } as float[]
                )
            }

            final var mutationResultR = col.insert(content)

            assert mutationResultR.data.insertCnt == nrRows
            final respFlush = col.flush();
            p respFlush

            // showCollections(name) is version-dependent and may return a
            // non-zero status even for an existing collection. The dedicated
            // hasCollection RPC is the stable existence contract.
            assert col.exists()

            // row_count is only eventually consistent after flush() (which
            // itself is async on the server) - poll briefly instead of
            // asserting immediately, rather than failing on a normal race.
            def stats
            final deadline = System.currentTimeMillis() + 10_000
            while (true) {
                final statistics = col.statistics
                stats = statistics?.data?.statsList?.find { it.key == 'row_count' }
                if (stats != null && stats.value.toInteger() == nrRows) break
                if (System.currentTimeMillis() > deadline) break
                Thread.sleep(250)
            }
            assert stats != null && stats.key == 'row_count'
            assert stats.value.toInteger() == nrRows

        } finally {
            this.milvusService.dropCollection(colname)
        }
    }
}
