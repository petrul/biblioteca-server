package ro.editii.scriptorium.collection

import org.apache.commons.io.output.NullWriter
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.MultilangTeiRepoConfig
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.TextbaseServer
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.model.AppUser
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.security.AppUserRegistrationService
import ro.editii.scriptorium.service.AdminService
import ro.editii.scriptorium.service.DivService
import ro.editii.scriptorium.tei.TeiRepo
import ro.editii.scriptorium.vector.FakeEmbedderTestConfig
import ro.editii.scriptorium.vector.NetworkFreeVectorTestConfig

import static ro.editii.scriptorium.TestUtils.TEI_ELEM

/**
 * Same fixture/style as DivCollectionServiceTest - exercises
 * ReadingProgressService directly, not through HTTP/Authentication.
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:readingProgressServiceTestDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "milvus.host=mini.local",
        "milvus.port=20112",
        "milvus.collection=test_tb_paras_qwen3_embedding_4b_collection_test_unused",
        "embeddings.host=mini.local",
        "embeddings.port=11200",
        "ollama.host=zmeu.local",
        "ollama.port=11434",
        "textbase.advertised.url=http://localhost:8080"
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TextbaseServer.class, TestConfig.class, FakeEmbedderTestConfig.class,
                   MultilangTeiRepoConfig.class, NetworkFreeVectorTestConfig.class])
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadingProgressServiceTest {

    @Autowired ReadingProgressService readingProgressService
    @Autowired AppUserRegistrationService registrationService
    @Autowired AdminService adminService
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired TeiDivRepository teiDivRepository
    @Autowired DivService divService
    @Autowired TeiRepo teiRepo

    TeiDiv opus
    TeiDiv secondOpus
    List<TeiDiv> chapterDivs
    AppUser user

    @BeforeAll
    void beforeAll() {
        TestUtils.truncateAllTables(this.jdbcTemplate)
        this.adminService.reimportAllTeis(new NullWriter())
        assert this.jdbcTemplate.queryForObject("select count(*) from " + TEI_ELEM, Integer.class) > 0

        final opera = this.teiDivRepository.findOpera(PageRequest.of(0, 20)).content
        assert opera.size() >= 2
        opera.each { it.setTeiRepo(this.teiRepo) }

        this.chapterDivs = []
        for (final candidate in opera) {
            final divs = []
            for (int n = 1; n <= 200 && divs.size() < 2; n++) {
                try {
                    final child = this.divService.childElem(candidate, n)
                    if (child.isDiv()) divs.add((TeiDiv) child)
                } catch (Throwable ignored) {
                    break
                }
            }
            if (divs.size() >= 2) {
                this.opus = candidate
                this.chapterDivs = divs
                break
            }
        }
        assert this.opus != null
        assert this.chapterDivs.size() >= 2
        this.secondOpus = opera.find { it.id != this.opus.id }
        assert this.secondOpus != null

        this.user = this.registrationService.register("reader_" + System.nanoTime(), "correcthorsebattery")
    }

    @Test
    void noSavedPositionUntilOneIsSet() {
        final otherUser = this.registrationService.register("fresh_" + System.nanoTime(), "correcthorsebattery")
        assert !this.readingProgressService.get(otherUser, this.opus.completePath).isPresent()
        assert this.readingProgressService.listAll(otherUser).isEmpty()
    }

    @Test
    void savesAndRetrievesAPosition() {
        final firstChapter = this.chapterDivs[0]
        this.readingProgressService.save(this.user, firstChapter.completePath)

        final saved = this.readingProgressService.get(this.user, this.opus.completePath)
        assert saved.isPresent()
        assert saved.get().div.id == firstChapter.id
        assert saved.get().opus.id == this.opus.id
    }

    @Test
    void savingAgainInTheSameWorkReplacesThePositionRatherThanAddingAnother() {
        final name = "advance_" + System.nanoTime()
        final user = this.registrationService.register(name, "correcthorsebattery")

        this.readingProgressService.save(user, this.chapterDivs[0].completePath)
        this.readingProgressService.save(user, this.chapterDivs[1].completePath)

        final saved = this.readingProgressService.get(user, this.opus.completePath)
        assert saved.isPresent()
        assert saved.get().div.id == this.chapterDivs[1].id

        // still exactly one row for this (user, opus) - not two
        assert this.readingProgressService.listAll(user).size() == 1
    }

    @Test
    void tracksPositionsInMultipleWorksIndependently() {
        final name = "multiwork_" + System.nanoTime()
        final user = this.registrationService.register(name, "correcthorsebattery")

        this.readingProgressService.save(user, this.chapterDivs[0].completePath)
        this.readingProgressService.save(user, this.secondOpus.completePath)

        final all = this.readingProgressService.listAll(user)
        assert all.size() == 2
        assert all.any { it.opus.id == this.opus.id && it.div.id == this.chapterDivs[0].id }
        assert all.any { it.opus.id == this.secondOpus.id && it.div.id == this.secondOpus.id }
    }

    @Test
    void positionsAreIsolatedPerUser() {
        final otherUser = this.registrationService.register("isolated_" + System.nanoTime(), "correcthorsebattery")
        this.readingProgressService.save(this.user, this.chapterDivs[0].completePath)

        assert !this.readingProgressService.get(otherUser, this.opus.completePath).isPresent()
    }

    @Test
    void rejectsAPathThatDoesNotResolveToADiv() {
        final ex = shouldFail { this.readingProgressService.save(this.user, "not/a/real/path") }
        assert ex != null
    }

    static Exception shouldFail(Closure closure) {
        try {
            closure.call()
        } catch (Exception e) {
            return e
        }
        throw new AssertionError("expected an exception but none was thrown")
    }
}
