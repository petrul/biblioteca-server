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
import ro.editii.scriptorium.dto.ReadingProgressDto
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
        "milvus.address=mini.local:20112",
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
    void touchCountIncrementsOnEachSaveAndDrivesTheReadFlag() {
        final name = "touches_" + System.nanoTime()
        final user = this.registrationService.register(name, "correcthorsebattery")

        this.readingProgressService.save(user, this.chapterDivs[0].completePath)
        final firstSave = this.readingProgressService.get(user, this.opus.completePath).get()
        assert firstSave.touchCount == 1
        assert !ReadingProgressDto.from(firstSave).read

        this.readingProgressService.save(user, this.chapterDivs[1].completePath)
        this.readingProgressService.save(user, this.chapterDivs[0].completePath)

        final thirdSave = this.readingProgressService.get(user, this.opus.completePath).get()
        assert thirdSave.touchCount == 3
        assert ReadingProgressDto.from(thirdSave).read
    }

    @Test
    void attentionAccumulatesOnTopOfAnExistingPosition() {
        final name = "attention_" + System.nanoTime()
        final user = this.registrationService.register(name, "correcthorsebattery")

        this.readingProgressService.save(user, this.chapterDivs[0].completePath)
        this.readingProgressService.addAttention(user, this.opus.completePath, 45L)
        this.readingProgressService.addAttention(user, this.opus.completePath, 30L)

        final saved = this.readingProgressService.get(user, this.opus.completePath).get()
        assert saved.attentionSeconds == 75L
    }

    @Test
    void rejectsAttentionForAWorkWithNoSavedPositionYet() {
        final otherUser = this.registrationService.register("noattention_" + System.nanoTime(), "correcthorsebattery")
        final ex = shouldFail { this.readingProgressService.addAttention(otherUser, this.opus.completePath, 10L) }
        assert ex != null
    }

    @Test
    void scrollFractionIsSavedAndReadBack() {
        final name = "scroll_" + System.nanoTime()
        final user = this.registrationService.register(name, "correcthorsebattery")

        this.readingProgressService.save(user, this.chapterDivs[0].completePath)
        this.readingProgressService.updateScrollPosition(user, this.opus.completePath, this.chapterDivs[0].completePath, 0.42d)

        final saved = this.readingProgressService.get(user, this.opus.completePath).get()
        assert saved.scrollFraction == 0.42d
    }

    @Test
    void scrollFractionIsClampedToZeroOneEvenIfTheCallerSendsOutOfRange() {
        final name = "scrollclamp_" + System.nanoTime()
        final user = this.registrationService.register(name, "correcthorsebattery")

        this.readingProgressService.save(user, this.chapterDivs[0].completePath)
        this.readingProgressService.updateScrollPosition(user, this.opus.completePath, this.chapterDivs[0].completePath, 1.7d)
        assert this.readingProgressService.get(user, this.opus.completePath).get().scrollFraction == 1.0d

        this.readingProgressService.updateScrollPosition(user, this.opus.completePath, this.chapterDivs[0].completePath, -0.3d)
        assert this.readingProgressService.get(user, this.opus.completePath).get().scrollFraction == 0.0d
    }

    @Test
    void reSavingTheSameDivPreservesScrollFractionButANewDivResetsIt() {
        final name = "scrollreset_" + System.nanoTime()
        final user = this.registrationService.register(name, "correcthorsebattery")

        this.readingProgressService.save(user, this.chapterDivs[0].completePath)
        this.readingProgressService.updateScrollPosition(user, this.opus.completePath, this.chapterDivs[0].completePath, 0.65d)

        // Resuming the SAME div ("Continue Reading") must not wipe the position being resumed to.
        this.readingProgressService.save(user, this.chapterDivs[0].completePath)
        assert this.readingProgressService.get(user, this.opus.completePath).get().scrollFraction == 0.65d

        // Turning the page to a genuinely different div starts fresh at its own top.
        this.readingProgressService.save(user, this.chapterDivs[1].completePath)
        assert this.readingProgressService.get(user, this.opus.completePath).get().scrollFraction == 0.0d
    }

    @Test
    void aStaleScrollUpdateForAnOldDivIsIgnoredOnceTheReaderHasMovedOn() {
        final name = "scrollstale_" + System.nanoTime()
        final user = this.registrationService.register(name, "correcthorsebattery")

        this.readingProgressService.save(user, this.chapterDivs[0].completePath)
        this.readingProgressService.save(user, this.chapterDivs[1].completePath)

        // Late update naming the chapter the reader has since left - ignored, not an error.
        this.readingProgressService.updateScrollPosition(user, this.opus.completePath, this.chapterDivs[0].completePath, 0.9d)

        final saved = this.readingProgressService.get(user, this.opus.completePath).get()
        assert saved.div.id == this.chapterDivs[1].id
        assert saved.scrollFraction == 0.0d
    }

    @Test
    void rejectsScrollPositionForAWorkWithNoSavedPositionYet() {
        final otherUser = this.registrationService.register("noscroll_" + System.nanoTime(), "correcthorsebattery")
        final ex = shouldFail { this.readingProgressService.updateScrollPosition(otherUser, this.opus.completePath, this.chapterDivs[0].completePath, 0.5d) }
        assert ex != null
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
