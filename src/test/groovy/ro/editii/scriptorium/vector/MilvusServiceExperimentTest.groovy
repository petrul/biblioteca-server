package ro.editii.scriptorium.vector


import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Disabled
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
        classes = [ VectorConfig.class, MilvusService.class, VectorSearchAvailability.class, VectorTextSearchService.class, ro.editii.scriptorium.health.OllamaHealthTracker.class],
        properties = [
                "spring.main.allow-bean-definition-overriding=true",
                // The milvus beans only exist in milvus mode (qdrant is the
                // default store), and this experiment talks to the dedicated
                // integration milvus - not whatever VECTORSTORE_URL points
                // the default at.
                "vector.store=milvus",
                "vectorstore.address=http://srv2.local:20112"
        ])
@Import(TestConfig.class)
@Tag("integration-test")
@Disabled("Milvus is retired infrastructure - the vector store itest path is qdrant now (QdrantCollectionITest). Re-enable only if a dedicated milvus instance is ever stood up again.")
class MilvusServiceExperimentTest {

    @Autowired MilvusService milvusService

    @Test
    void collectionExists() {
        final name = 'cannotexist_' + TestUtils.randomString(20)
        final MilvusCollection col = this.milvusService[name]
        boolean created = false
        try {
            try {
                assert ! col.exists()
            } catch (RuntimeException unavailable) {
                Assumptions.assumeTrue(false, "Milvus is unavailable at the configured endpoint: ${unavailable.message}")
            }
            col.create(200)
            created = true
            assert col.exists()
        } finally {
            if (created) {
                col.drop()
                assert !col.exists()
            }
        }
    }

    @Test
    void createAndDeleteVectors() {
        final dim = MilvusCollection.DIM_768
        final colname = "test_" + TestUtils.randomString(10)

        final MilvusCollection col = this.milvusService[colname]
        boolean created = false

        try {
            try {
                // Probe the connection before creating anything.  If the
                // integration dependency is down, skip cleanly instead of
                // dereferencing null SDK responses or retrying forever.
                col.exists()
            } catch (RuntimeException unavailable) {
                Assumptions.assumeTrue(false, "Milvus is unavailable at the configured endpoint: ${unavailable.message}")
            }
            col.create(dim)
            created = true
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
            if (created) {
                this.milvusService.dropCollection(colname)
            }
        }
    }
}
