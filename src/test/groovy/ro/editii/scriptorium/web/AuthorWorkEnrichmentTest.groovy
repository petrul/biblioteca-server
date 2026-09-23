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
import ro.editii.scriptorium.dao.AuthorMediaAssociationRepository
import ro.editii.scriptorium.enrichment.EnrichmentService
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.Languages

/**
 * The ONE test in this whole suite that makes real network calls to
 * SearXNG, Wikipedia, and Wikidata. Deliberately calls the pipeline ONCE -
 * one author, one enrichAuthorAsync call - not "import a whole TEI file
 * and let postImportHooks fan out to every author/opus it contains": that
 * fanout (9 opus threads + 1 author thread for Creanga's own file alone)
 * is exactly what used to make this test flaky/slow for reasons that had
 * nothing to do with whether the search/reference integration itself
 * actually works.
 *
 * The call demonstrates search -> trusted Wikipedia material -> Wikidata
 * facts -> persistence. The exact image is deliberately not asserted
 * because external search rankings change; only URL-only persistence is.
 *
 * Calls EnrichmentService.enrichAuthorAsync() directly against a
 * hand-built Author, bypassing AdminService/TeiRepo/Lucene entirely -
 * none of that machinery is what this test is actually about.
 */
@Tag("integration-test")
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:aiEnrichmentITestDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "lucene.index.dir=\${java.io.tmpdir}/enrichmentitest-lucene-index",
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
class AuthorWorkEnrichmentTest {

    @Autowired EnrichmentService enrichmentService
    @Autowired AuthorRepository authorRepository
    @Autowired AuthorMediaAssociationRepository authorMediaAssociationRepository

    @Test
    void enrichmentEventuallyFillsInARealAuthorBioAndFacts() {
        final author = Author.newFromOriginalNameInTeiFile('Creangă, Ion')
        author.strId = 'ai-enrichment-itest-creanga'
        this.authorRepository.save(author)

        // The one real call this whole suite makes to the enrichment sources -
        // everything else about this feature should be tested with mocks.
        this.enrichmentService.enrichAuthorAsync(author, [], Languages.RO)

        final enriched = pollUntilNotNull(120_000) {
            final a = this.authorRepository.findByStrId('ai-enrichment-itest-creanga').first()
            a.bio != null ? a : null
        }
        assert enriched != null: "author bio enrichment did not complete within the timeout"
        assert !enriched.bio.isBlank()
        assert enriched.bioSourceUrl != null && enriched.bioSourceUrl.startsWith('http')
        assert enriched.birthDate != null && enriched.birthDate.startsWith('1837')
        assert enriched.deathDate != null && enriched.deathDate.startsWith('1889')
        assert enriched.birthPlace != null && !enriched.birthPlace.isBlank()
        assert enriched.writingLanguage == Languages.RO
        final images = pollUntilNotNull(15_000) {
            final refs = this.authorMediaAssociationRepository.findAllByAuthorPath('ai-enrichment-itest-creanga')
            refs ? refs : null
        }
        assert images != null
        assert images.every { it.mediaRef.role == 'enrichment' }
        assert images.every { it.mediaRef.url.startsWith('http') }
        assert images.size() <= 3

    }

    static <T> T pollUntilNotNull(long timeoutMs, Closure<T> check) {
        final deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            final result = check.call()
            if (result != null) return result
            Thread.sleep(250)
        }
        return null
    }
}
