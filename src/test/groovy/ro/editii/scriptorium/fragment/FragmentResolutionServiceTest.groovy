package ro.editii.scriptorium.fragment

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
import ro.editii.scriptorium.BibliotecaServer
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiElem
import ro.editii.scriptorium.service.AdminService
import ro.editii.scriptorium.service.ControllerTool
import ro.editii.scriptorium.service.DivService
import ro.editii.scriptorium.tei.TeiRepo
import ro.editii.scriptorium.vector.FakeEmbedderTestConfig
import ro.editii.scriptorium.vector.NetworkFreeVectorTestConfig

import static ro.editii.scriptorium.TestUtils.TEI_ELEM

/**
 * Uses MultilangTeiRepoConfig's small fixture set (see that class) - the
 * English fixture (Francis Bacon, "Of Gardens") specifically, found via
 * TeiDivRepository.findOperaByLang rather than a hardcoded author/opus
 * strId, so this doesn't depend on exactly how those slugs get generated.
 *
 * Test strategy: rather than hand-deriving real dot-paths against actual
 * TEI content (brittle, and this app's xpath/nth addressing isn't trivially
 * invertible from a known TeiElem back to a dot-path - see DivService.childElem's
 * doc comment), this discovers a handful of real, valid single-level
 * navigation paths by probing DivService.childElem directly (the same
 * primitive FragmentResolutionService itself uses), then asserts
 * self-consistency: resolving a Fragment through the dot-path API must
 * reproduce exactly what directly walking+rendering the same elements
 * produces.
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:fragmentResolutionServiceTestDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "vectorstore.address=http://mini.local:20112",
        "vector.collection=test_tb_paras_qwen3_embedding_4b_fragment_test_unused",
        "embeddings.host=mini.local",
        "embeddings.port=11200",
        "ollama.host=zmeu.local",
        "ollama.port=11434",
        "textbase.advertised.url=http://localhost:8080"
])
@SpringBootTest(
        // RANDOM_PORT (not NONE) - the app context eagerly wires web-layer
        // beans like ThymeleafViewResolver regardless of whether this test
        // makes any HTTP calls itself.
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [BibliotecaServer.class, TestConfig.class, FakeEmbedderTestConfig.class,
                   MultilangTeiRepoConfig.class, NetworkFreeVectorTestConfig.class])
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FragmentResolutionServiceTest {

    @Autowired FragmentResolutionService fragmentResolutionService
    @Autowired DivService divService
    @Autowired ControllerTool controllerTool
    @Autowired AdminService adminService
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired TeiDivRepository teiDivRepository
    @Autowired TeiRepo teiRepo

    TeiDiv opus
    List<Integer> directLeafSteps

    // The French fixture (Tourgueniev, "Poèmes en prose") is a collection
    // of several separate poems, each its own div2 sub-chapter directly
    // under the opus - i.e. the opus's own direct children are ALL divs,
    // none of them addressable text on their own. Used for the corner
    // cases genuinely needing real nesting (crossing a sub-chapter
    // boundary, landing on a div instead of text) that the flat English
    // essay above can't exercise.
    TeiDiv frenchOpus
    List<TeiDiv> frenchSubDivs
    // Parallel to frenchSubDivs - frenchOpus's own <head> is itself a
    // non-div direct child (step 1, same "step 1 is often the title, not a
    // sub-chapter" pattern already seen on the English opus above), so the
    // real per-poem navigation step is NOT the same as the poem's position
    // in frenchSubDivs and must be tracked explicitly, not assumed.
    List<Integer> frenchSubDivSteps

    @BeforeAll
    void beforeAll() {
        TestUtils.truncateAllTables(this.jdbcTemplate)
        this.adminService.reimportAllTeis(new NullWriter())
        assert this.jdbcTemplate.queryForObject("select count(*) from " + TEI_ELEM, Integer.class) > 0

        final english = this.teiDivRepository.findOperaByLang(Languages.EN, PageRequest.of(0, 5))
        assert english.hasContent()
        this.opus = english.content.first()
        this.opus.setTeiRepo(this.teiRepo)

        // Direct (single navigation step) non-div children of the opus, in
        // order - the essay's own paragraphs, not counting any front-matter
        // sub-divs. At least 2 are needed below for the multi-paragraph test.
        this.directLeafSteps = []
        for (int n = 1; n <= 30; n++) {
            try {
                final child = this.divService.childElem(this.opus, n)
                if (!child.isDiv())
                    this.directLeafSteps << n
            } catch (Exception ignored) {
                break // ran out of children
            }
        }
        assert this.directLeafSteps.size() >= 2: "expected at least 2 direct paragraph children in the English fixture"

        final french = this.teiDivRepository.findOperaByLang(Languages.FR, PageRequest.of(0, 5))
        assert french.hasContent()
        this.frenchOpus = french.content.first()
        this.frenchOpus.setTeiRepo(this.teiRepo)

        this.frenchSubDivs = []
        this.frenchSubDivSteps = []
        for (int n = 1; n <= 30; n++) {
            try {
                final child = this.divService.childElem(this.frenchOpus, n)
                if (child.isDiv()) {
                    this.frenchSubDivs << (TeiDiv) child
                    this.frenchSubDivSteps << n
                }
            } catch (Exception ignored) {
                break
            }
        }
        assert this.frenchSubDivs.size() >= 2: "expected at least 2 poem sub-chapters in the French fixture"
    }

    TeiElem leafAt(int step) {
        return this.divService.childElem(this.opus, step)
    }

    String textAt(int step) {
        return this.controllerTool.teiElemToString(leafAt(step).toElemInfo())
    }

    /** First non-div direct child step of an arbitrary parent (probing, same technique as beforeAll). */
    int firstLeafStepWithin(TeiElem parent) {
        for (int n = 1; n <= 30; n++) {
            try {
                if (!this.divService.childElem(parent, n).isDiv())
                    return n
            } catch (Exception ignored) {
                break
            }
        }
        throw new IllegalStateException("no non-div child found under ${parent}")
    }

    @Test
    void wholeParagraphRoundTrips() {
        final step = this.directLeafSteps[0]
        final text = textAt(step)
        assert !text.isBlank()

        final resolved = this.fragmentResolutionService.resolve(this.opus, "${step}.0", "${step}.${text.length()}")

        assert resolved.paragraphs == [text]
    }

    @Test
    void partialSubstringWithinOneParagraph() {
        // Not necessarily directLeafSteps[0] - that may be a short title
        // heading ("Of Gardens" itself, 10 chars) rather than body text;
        // pick whichever direct leaf has the most text instead of assuming.
        final step = this.directLeafSteps.max { textAt(it).length() }
        final text = textAt(step)
        assert text.length() > 10

        final resolved = this.fragmentResolutionService.resolve(this.opus, "${step}.2", "${step}.9")

        assert resolved.paragraphs == [text.substring(2, 9)]
    }

    @Test
    void spansMultipleParagraphsTrimmingBothEnds() {
        final startStep = this.directLeafSteps[0]
        final endStep = this.directLeafSteps[1]
        final startText = textAt(startStep)
        final endText = textAt(endStep)
        assert startText.length() > 5
        assert endText.length() > 5

        final resolved = this.fragmentResolutionService.resolve(
                this.opus, "${startStep}.3", "${endStep}.4")

        assert resolved.paragraphs.size() == (endStep - startStep + 1) // includes any intermediate direct paragraphs
        assert resolved.paragraphs.first() == startText.substring(3)
        assert resolved.paragraphs.last() == endText.substring(0, 4)
    }

    @Test
    void rejectsAReversedRange() {
        final startStep = this.directLeafSteps[1]
        final endStep = this.directLeafSteps[0]

        final ex = shouldFail { this.fragmentResolutionService.resolve(this.opus, "${startStep}.0", "${endStep}.1") }
        assert ex.message.contains("before its start")
    }

    @Test
    void rejectsAnOffsetPastTheEndOfTheParagraph() {
        final step = this.directLeafSteps[0]
        final text = textAt(step)

        final ex = shouldFail { this.fragmentResolutionService.resolve(this.opus, "${step}.0", "${step}.${text.length() + 1000}") }
        assert ex.message.contains("out of range")
    }

    @Test
    void rejectsADotPathWithOnlyOneSegment() {
        final ex = shouldFail { DotPath.parse("5") }
        assert ex.message.contains("at least 2")
    }

    // ---- corner cases ----

    @Test
    void selectsExactlyOneCharacter() {
        // The feature's own stated smallest unit: "from a subchapter down
        // to a single character".
        final step = this.directLeafSteps.max { textAt(it).length() }
        final text = textAt(step)
        assert text.length() > 1

        final resolved = this.fragmentResolutionService.resolve(this.opus, "${step}.0", "${step}.1")

        assert resolved.paragraphs == [text.substring(0, 1)]
    }

    @Test
    void rejectsAZeroLengthFragmentWithinOneParagraph() {
        final step = this.directLeafSteps[0]

        final ex = shouldFail { this.fragmentResolutionService.resolve(this.opus, "${step}.3", "${step}.3") }
        assert ex.message.contains("selects no text")
    }

    @Test
    void rejectsAZeroLengthFragmentAtAParagraphBoundary() {
        // Structurally "valid" (start <= end, in range) but ends up
        // selecting nothing at all: the whole first paragraph is skipped
        // (end offset 0 on it) and the range stops there.
        final startStep = this.directLeafSteps[0]

        final ex = shouldFail { this.fragmentResolutionService.resolve(this.opus, "${startStep}.0", "${startStep}.0") }
        assert ex.message.contains("selects no text")
    }

    @Test
    void rejectsNavigationIndexZero() {
        final ex = shouldFail { DotPath.parse("0.5") }
        assert ex.message.contains(">= 1")
    }

    @Test
    void rejectsNavigationIndexZeroEvenAsTheSecondSegment() {
        final ex = shouldFail { DotPath.parse("1.0.5") }
        assert ex.message.contains(">= 1")
    }

    @Test
    void rejectsNonNumericSegments() {
        final ex = shouldFail { DotPath.parse("1.abc") }
        assert ex.message.contains("not a valid dot-number")
    }

    @Test
    void rejectsEmptySegmentsFromConsecutiveDots() {
        final ex = shouldFail { DotPath.parse("1..3") }
        assert ex.message.contains("not a valid dot-number")
    }

    @Test
    void rejectsABlankDotPath() {
        final ex = shouldFail { DotPath.parse("   ") }
        assert ex.message.contains("must not be blank")

        final ex2 = shouldFail { DotPath.parse(null) }
        assert ex2.message.contains("must not be blank")
    }

    @Test
    void rejectsAPathThatLandsOnASubChapterInsteadOfText() {
        // Navigating to the first poem's own step lands directly on a
        // div/sub-chapter, not on any of that poem's own paragraphs/verses -
        // not addressable as fragment text.
        final poemStep = this.frenchSubDivSteps[0]
        final ex = shouldFail { this.fragmentResolutionService.resolve(this.frenchOpus, "${poemStep}.0", "${poemStep}.5") }
        assert ex.message.contains("addressable paragraph")
    }

    @Test
    void spansAcrossASubChapterBoundary() {
        // The feature's other stated corner case: "a fragment can start in
        // a div and end in another". startLeaf lives inside the French
        // opus's FIRST poem, endLeaf inside its SECOND - two different
        // TeiDiv sub-chapters under the same opus.
        final firstPoem = this.frenchSubDivs[0]
        final secondPoem = this.frenchSubDivs[1]
        final firstPoemStep = this.frenchSubDivSteps[0]
        final secondPoemStep = this.frenchSubDivSteps[1]
        final startStep = firstLeafStepWithin(firstPoem)
        final endStep = firstLeafStepWithin(secondPoem)

        final startLeaf = this.divService.childElem(firstPoem, startStep)
        final endLeaf = this.divService.childElem(secondPoem, endStep)
        final startText = this.controllerTool.teiElemToString(startLeaf.toElemInfo())
        final endText = this.controllerTool.teiElemToString(endLeaf.toElemInfo())
        assert startText.length() > 3
        assert endText.length() > 3

        final resolved = this.fragmentResolutionService.resolve(
                this.frenchOpus, "${firstPoemStep}.${startStep}.2", "${secondPoemStep}.${endStep}.3")

        assert resolved.paragraphs.size() >= 2
        assert resolved.paragraphs.first() == startText.substring(2)
        assert resolved.paragraphs.last() == endText.substring(0, 3)
    }

    @Test
    void wholeSubChapterFragmentSpansEveryOneOfItsParagraphs() {
        // Whole-subchapter quoting: start at the very beginning of the
        // first poem's own leaf list, end at the very end of its last one.
        final firstPoem = this.frenchSubDivs[0]
        final poemLeaves = this.divService.getParagraphs(firstPoem)
        assert poemLeaves.size() >= 1

        final firstLeafStep = firstLeafStepWithin(firstPoem)
        final lastLeafText = this.controllerTool.teiElemToString(poemLeaves.last().toElemInfo())

        // Reuse the already-known first-leaf step for the start point, and
        // locate the last leaf's own step the same way (by probing) so this
        // doesn't assume the poem has exactly one paragraph.
        int lastLeafStep = -1
        for (int n = 1; n <= 30; n++) {
            try {
                final candidate = this.divService.childElem(firstPoem, n)
                if (!candidate.isDiv() && candidate.completePath == poemLeaves.last().completePath) {
                    lastLeafStep = n
                    break
                }
            } catch (Exception ignored) {
                break
            }
        }
        assert lastLeafStep > 0

        final resolved = this.fragmentResolutionService.resolve(
                firstPoem, "${firstLeafStep}.0", "${lastLeafStep}.${lastLeafText.length()}")

        assert resolved.paragraphs.size() == poemLeaves.size()
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
