package ro.editii.scriptorium.service

import org.apache.commons.io.output.NullWriter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.Util
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.service.LanguageDetectionService
import ro.editii.scriptorium.scheduled.NoWriter
import ro.editii.scriptorium.tei.TeiDirRepoImpl
import ro.editii.scriptorium.tei.TeiRepo

// Was WebITest.destroyAllExistingAndReimportAllTeis() - moved out for the
// same reason relocationWorks() was (see RelocationTest.groovy): it never
// needed the shared 4-file/6MB testrepo fixture, just *a* repo to destroy
// and reimport. Riding on WebITest's fixture cost ~84s per run for a test
// that, until now, had zero assertions of its own - purely "doesn't
// throw". This two-file fixture keeps the thing that actually makes this
// test valuable - it's a real end-to-end run through AdminService, the
// real TeiDirRepoImpl/TeifileParser, the real Derby datasource and the
// real LuceneIndexService, not a mock of any of them - while making the
// "doesn't throw" guarantee into a real one: both the first and the last
// file in repo-iteration order must come back out correctly after a
// destroy+reimport, not just whichever one happens to be processed first.
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:derby:memory:destroyReimportTestDb;create=true",
        "spring.datasource.driver-class-name=org.apache.derby.iapi.jdbc.AutoloadedDriver",
        "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create"
])
@SpringBootTest(classes = [TestConfig.class, AdminServiceDestroyAllExistingAndReimportAllTeisTest.TinyRepoConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
class AdminServiceDestroyAllExistingAndReimportAllTeisTest {

    // Overrides TestConfig's own teiRepo() bean (allow-bean-definition-
    // overriding=true above) with a 2-file fixture instead of the shared
    // 4-file/6MB one - a_/z_ prefixes so alphabetic repo-directory
    // traversal order is unambiguous regardless of filesystem specifics.
    @TestConfiguration
    static class TinyRepoConfig {
        @Bean
        TeiRepo teiRepo() {
            final String dirname = Util.urlToFileString(
                    getClass().getClassLoader().getResource("destroy-reimport-testrepo"))
            return new TeiDirRepoImpl(dirname)
        }
    }

    @Autowired AdminService adminService
    @Autowired TeiDivRepository teiDivRepository

    @MockitoBean TextbaseEventsPublisher textbaseEventsPublisher

    // Lingua eagerly loads an n-gram model per language at first detection
    // (see LanguageDetectionService) - GBs of heap for the ~18 languages of
    // the Languages enum, which OOMs a memory-tight CI agent (observed on
    // srv2: OutOfMemoryError in fastutil's Object2FloatOpenHashMap mid-
    // model-load, then every Tomcat poller starved). This test exercises
    // the destroy/reimport mechanics, not language detection - a stubbed
    // fixed answer keeps the import path real while keeping the multi-GB
    // models out of the test JVM entirely.
    @MockitoBean LanguageDetectionService languageDetectionService

    @org.junit.jupiter.api.BeforeEach
    void languageIsAlwaysEnglish() {
        org.mockito.Mockito.when(languageDetectionService.detect(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(Languages.EN))
    }

    @Test
    void destroyAllExistingAndReimportAllTeisReimportsEveryFileFirstToLast() {
        adminService.reimportAllTeis(new NullWriter())
        final all = teiDivRepository.findAll()
        println "DEBUG: teiDivRepository.findAll() = ${all.collect { [id: it.id, head: it.head, completePath: it.completePath] }}"
        assert teiDivRepository.findAll().any { it.head == 'First Opus' }

        adminService.destroyAllExistingAndReimportAllTeis(new NoWriter(), true)

        final afterReimport = teiDivRepository.findAll()
        // Both ends of the repo listing must have survived the destroy
        // and come back from the reimport - not just the first file
        // processed (the whole point of exercising 2 files, not 1).
        assert afterReimport.any { it.head == 'First Opus' }
        assert afterReimport.any { it.head == 'Second Opus' }
    }
}
