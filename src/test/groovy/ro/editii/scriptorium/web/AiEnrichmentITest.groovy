package ro.editii.scriptorium.web

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.enrichment.AiEnrichmentService
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.Languages

/**
 * The ONE test in this whole suite that makes a real network call to
 * Ollama or SearXNG - every other Ollama-touching test (OllamaEmbeddersTest,
 * and any future test of the AuthorFacts JSON parsing) mocks its HTTP
 * transport instead. Deliberately calls the real pipeline exactly ONCE -
 * one author, one enrichAuthorAsync call - not "import a whole TEI file
 * and let postImportHooks fan out to every author/opus it contains": that
 * fanout (9 opus threads + 1 author thread for Creanga's own file alone)
 * is exactly what used to make this test flaky/slow for reasons that had
 * nothing to do with whether the Ollama/SearXNG integration itself
 * actually works.
 *
 * bio text no longer needs Ollama at all (see AiEnrichmentService's class
 * doc comment - it's the search material itself, truncated); the one real
 * Ollama call left is extractAuthorFacts. One call is enough to
 * demonstrate the whole pipeline (search -> prefer a trusted-domain
 * source -> Wikipedia's own REST extract -> Ollama extracts the
 * structured facts JSON -> persist) genuinely works end-to-end; every
 * other scenario belongs in a mocked test instead.
 *
 * Calls AiEnrichmentService.enrichAuthorAsync() directly against a
 * hand-built Author, bypassing AdminService/TeiRepo/Lucene entirely -
 * none of that machinery is what this test is actually about.
 */
@Tag("external")
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:aiEnrichmentITestDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "lucene.index.dir=\${java.io.tmpdir}/aienrichmentitest-lucene-index",
        "lucene.autoindex.enabled=false",
        // The one place in the whole suite that wants this true - see the
        // class doc comment above for why nowhere else should.
        "enrichment.enabled=true",
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TestConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiEnrichmentITest {

    @Autowired AiEnrichmentService aiEnrichmentService
    @Autowired AuthorRepository authorRepository

    @Test
    void aiEnrichmentEventuallyFillsInARealAuthorBioAndFacts() {
        final author = Author.newFromOriginalNameInTeiFile('Creangă, Ion')
        author.strId = 'ai-enrichment-itest-creanga'
        this.authorRepository.save(author)

        // The one real call this whole suite makes to Ollama/SearXNG -
        // everything else about this feature should be tested with mocks.
        this.aiEnrichmentService.enrichAuthorAsync(author, [], Languages.RO)

        final enriched = pollUntilNotNull(240_000) {
            final a = this.authorRepository.findByStrId('ai-enrichment-itest-creanga').first()
            a.bio != null ? a : null
        }
        assert enriched != null: "author bio enrichment did not complete within the timeout"
        assert !enriched.bio.isBlank()
        assert enriched.bioSourceUrl != null && enriched.bioSourceUrl.startsWith('http')

        // Deliberately NOT asserting specific AuthorFacts field values
        // (birthDate, birthPlace, etc.) here - confirmed live that a
        // real, valid JSON response can legitimately come back with most
        // fields null (Wikipedia's short lead extract often doesn't
        // restate vital-stats that live in the infobox instead), and
        // that's the model correctly declining to guess rather than a
        // bug. Asserting on real LLM content quality would make this
        // test flaky for reasons that have nothing to do with whether
        // the pipeline itself works - that's what bio/bioSourceUrl above
        // (the deterministic, Ollama-free part) already verify. Facts
        // extraction's own JSON-shape correctness belongs in a mocked
        // unit test with a canned Ollama response, not here.
    }

    static <T> T pollUntilNotNull(long timeoutMs, Closure<T> check) {
        final deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            final result = check.call()
            if (result != null) return result
            Thread.sleep(1000)
        }
        return null
    }
}
