package ro.editii.scriptorium.vector


import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils

import static ro.editii.scriptorium.GTestUtil.p

@SpringBootTest(
        classes = [ VectorConfig.class, MilvusService.class, VectorSearchAvailability.class, MilvusTextSearchService.class, ro.editii.scriptorium.health.OllamaHealthTracker.class],
        properties = [
                "milvus.address=zmeu.local:20112",
                "embedder.address=zmeu.local:11434",
                "spring.main.allow-bean-definition-overriding=true"
        ])
@Import(TestConfig.class)
@Tag("external")
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
        col.createIndexIvfSq8()
        col.load()

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

            final colinfo = this.milvusService.getCollectionInfo(colname)
            assert colinfo.data.getCollectionNames(0) == col.name

            // row_count is only eventually consistent after flush() (which
            // itself is async on the server) - poll briefly instead of
            // asserting immediately, rather than failing on a normal race.
            def stats
            final deadline = System.currentTimeMillis() + 10_000
            while (true) {
                stats = col.statistics.data.statsList.get(0)
                if (stats.key == 'row_count' && stats.value.toInteger() == nrRows) break
                if (System.currentTimeMillis() > deadline) break
                Thread.sleep(250)
            }
            assert stats.key == 'row_count'
            assert stats.value.toInteger() == nrRows

        } finally {
            this.milvusService.dropCollection(colname)
        }
    }
}
