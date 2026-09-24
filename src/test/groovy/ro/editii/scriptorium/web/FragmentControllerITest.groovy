package ro.editii.scriptorium.web

import org.apache.commons.io.output.NullWriter
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.HttpClientErrorException
import ro.editii.scriptorium.MultilangTeiRepoConfig
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.BibliotecaServer
import ro.editii.scriptorium.client.TextbaseClient
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.service.AdminService
import ro.editii.scriptorium.service.ControllerTool
import ro.editii.scriptorium.service.DivService
import ro.editii.scriptorium.tei.TeiRepo
import ro.editii.scriptorium.vector.FakeEmbedderTestConfig
import ro.editii.scriptorium.vector.NetworkFreeVectorTestConfig

import static ro.editii.scriptorium.TestUtils.TEI_ELEM

/**
 * End-to-end HTTP test of GET /quote/{divPath}?start=&end= (FragmentController
 * + quote.html), against MultilangTeiRepoConfig's small fixture set (see
 * that class). The resolution algorithm itself is covered in detail by
 * FragmentResolutionServiceTest; this just confirms the route/rendering
 * wiring - path resolution, param binding, the Thymeleaf template - works
 * end to end.
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:fragmentControllerITestDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "milvus.url=http://mini.local:20112",
        "milvus.collection=test_tb_paras_qwen3_embedding_4b_fragment_ctrl_test_unused",
        "embeddings.host=mini.local",
        "embeddings.port=11200",
        "ollama.host=zmeu.local",
        "ollama.port=11434",
        "textbase.advertised.url=http://localhost:8080"
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [BibliotecaServer.class, TestConfig.class, FakeEmbedderTestConfig.class,
                   MultilangTeiRepoConfig.class, NetworkFreeVectorTestConfig.class])
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FragmentControllerITest {

    @Autowired @Lazy TextbaseClient tbc
    @Autowired AdminService adminService
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired TeiDivRepository teiDivRepository
    @Autowired DivService divService
    @Autowired ControllerTool controllerTool
    @Autowired TeiRepo teiRepo

    TeiDiv opus
    int leafStep
    String leafText

    @BeforeAll
    void beforeAll() {
        TestUtils.truncateAllTables(this.jdbcTemplate)
        this.adminService.reimportAllTeis(new NullWriter())
        assert this.jdbcTemplate.queryForObject("select count(*) from " + TEI_ELEM, Integer.class) > 0

        final english = this.teiDivRepository.findOperaByLang(Languages.EN, PageRequest.of(0, 5))
        this.opus = english.content.first()
        this.opus.setTeiRepo(this.teiRepo)

        // Find a direct paragraph child with real body text (not the short
        // title heading) - same probing technique as FragmentResolutionServiceTest.
        String bestText = ""
        int bestStep = -1
        for (int n = 1; n <= 30; n++) {
            try {
                final child = this.divService.childElem(this.opus, n)
                if (!child.isDiv()) {
                    final text = this.controllerTool.teiElemToString(child.toElemInfo())
                    if (text.length() > bestText.length()) {
                        bestText = text
                        bestStep = n
                    }
                }
            } catch (Exception ignored) {
                break
            }
        }
        assert bestStep > 0
        this.leafStep = bestStep
        this.leafText = bestText
    }

    @Test
    void rendersAQuoteCardForAValidFragment() {
        final path = "/quote/${this.opus.completePath}?start=${this.leafStep}.0&end=${this.leafStep}.${Math.min(40, this.leafText.length())}"
        final html = this.tbc.getTextHtml(path)

        assert html.contains("quote-card")
        assert html.contains("quote-citation")
        assert html.contains(this.leafText.substring(0, Math.min(40, this.leafText.length())))
    }

    @Test
    void rendersBaconsFamousOpeningLineAsAQuoteCard() {
        // A real, recognizable "golden path" example, not just a synthetic
        // one: Francis Bacon's "Of Gardens" opens with one of its most
        // quoted lines, nested two levels down from the opus (the opus
        // itself is a title-page wrapper div; the essay proper is a div2
        // sub-chapter under it - see testrepo-search/en). Found by probing
        // real structure, not hardcoded indices - same technique as
        // FragmentResolutionServiceTest.
        int essaySubDivStep = -1
        for (int n = 1; n <= 10; n++) {
            if (this.divService.childElem(this.opus, n).isDiv()) {
                essaySubDivStep = n
                break
            }
        }
        assert essaySubDivStep > 0
        final essayDiv = this.divService.childElem(this.opus, essaySubDivStep)

        int openingLineStep = -1
        String openingLine = null
        for (int n = 1; n <= 10; n++) {
            final child = this.divService.childElem(essayDiv, n)
            if (!child.isDiv()) {
                final text = this.controllerTool.teiElemToString(child.toElemInfo())
                if (text.contains("planted a garden")) {
                    openingLineStep = n
                    openingLine = text
                    break
                }
            }
        }
        assert openingLineStep > 0: "expected to find Bacon's opening line in the essay sub-chapter"

        // The whole essay is one single TEI paragraph in this source (no
        // internal <p> breaks) - a substring of it, not the whole thing, is
        // the actual quotable line: "GOD almighty first planted a garden:
        // and indeed it is the purest of human pleasures."
        final sentenceEnd = openingLine.indexOf("It is the greatest refreshment")
        assert sentenceEnd > 0
        final expectedQuote = openingLine.substring(0, sentenceEnd).trim()

        final path = "/quote/${this.opus.completePath}?start=${essaySubDivStep}.${openingLineStep}.0" +
                "&end=${essaySubDivStep}.${openingLineStep}.${sentenceEnd}"
        final html = this.tbc.getTextHtml(path)

        assert html.contains(expectedQuote)
        assert html.contains("Francis Bacon")
        println "=== GET ${path} ==="
        println html
    }

    @Test
    void rejectsAnInvalidFragmentWith400() {
        final path = "/quote/${this.opus.completePath}?start=${this.leafStep}.0&end=${this.leafStep}.999999"

        final ex = shouldFail(HttpClientErrorException) { this.tbc.getTextHtml(path) }
        assert ex.statusCode.value() == 400
    }

    static Exception shouldFail(Class expectedType, Closure closure) {
        try {
            closure.call()
        } catch (Exception e) {
            assert expectedType.isInstance(e): "expected ${expectedType} but got ${e.class}: ${e.message}"
            return e
        }
        throw new AssertionError("expected an exception but none was thrown")
    }
}
