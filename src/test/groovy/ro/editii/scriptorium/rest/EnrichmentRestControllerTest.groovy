package ro.editii.scriptorium.rest

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.dao.AuthorMediaAssociationRepository
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.DivMediaAssociationRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiFile

/**
 * The Java half of the enrichment pipeline after the external-call half
 * (search -> trusted Wikipedia material -> Wikidata facts) moved to the
 * biblioteca-nestjs worker: this server is now just the narrow, idempotent
 * persistence boundary (EnrichmentRestController) that worker POSTs to.
 * No network calls at all anymore - the payload the worker would have
 * derived from Wikipedia/Wikidata is hand-built here, and what gets
 * exercised is the contract both sides rely on:
 *
 * - blank bio/summary/facts fields are filled, existing values are NEVER
 *   overwritten (a crashed-and-rerun sweep is harmless)
 * - image URLs become role=enrichment MediaRefs plus associations, capped
 *   at 3, and a second POST adds nothing
 * - an unknown author/opus is a clean 404, not a partial write
 *
 * The real-network side of the feature (actual Wikipedia/Wikidata
 * lookups) lives with the code that makes those calls now: see
 * biblioteca-nestjs's enrichment.service.spec.ts.
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:enrichmentCtrlTestDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.main.allow-bean-definition-overriding=true",
        "lucene.autoindex.enabled=false",
])
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TestConfig.class])
@AutoConfigureTestRestTemplate
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnrichmentRestControllerTest {

    @Autowired private TestRestTemplate restTemplate
    @Autowired private AuthorRepository authorRepository
    @Autowired private TeiDivRepository teiDivRepository
    @Autowired private TeiFileRepository teiFileRepository
    @Autowired private AuthorMediaAssociationRepository authorMediaAssociationRepository
    @Autowired private DivMediaAssociationRepository divMediaAssociationRepository

    private static final String AUTHOR_STR_ID = "enrichment-ctrl-test-creanga"
    private static final String OPUS_IMAGE = "https://upload.wikimedia.org/wikipedia/commons/povesti.jpg"

    // original_name_in_tei_file is UNIQUE - the two tests build different
    // authors, so they can't share the same original name (the strId alone
    // differs).
    private Author authorWith(String originalNameInTeiFile, String strId) {
        final author = Author.newFromOriginalNameInTeiFile(originalNameInTeiFile)
        author.strId = strId
        return this.authorRepository.save(author)
    }

    private TeiDiv opusOf(Author author, String filename, String urlFragment, String head) {
        final teiFile = new TeiFile()
        teiFile.filename = filename
        teiFile.authors = [author]
        teiFile.language = Languages.RO
        this.teiFileRepository.save(teiFile)
        final opus = new TeiDiv()
        opus.teiFile = teiFile
        opus.urlFragment = urlFragment
        opus.head = head
        return this.teiDivRepository.save(opus)
    }

    private post(Map<String, Object> update) {
        return this.restTemplate.postForEntity("/api/internal/enrichment", update, String.class)
    }

    @Test
    void authorUpdateFillsBlankFieldsOnceAndNeverOverwrites() {
        final author = authorWith("Creangă, Ion", AUTHOR_STR_ID)

        final response = post([
                authorStrId: AUTHOR_STR_ID,
                bio: "Ion Creangă was a Romanian writer.",
                bioSourceUrl: "https://en.wikipedia.org/wiki/Ion_Creangă",
                birthDate: "1837",
                deathDate: "1889",
                birthPlace: "Târgu Neamț",
                country: "Romania",
                writingLanguage: "RO",
                imageUrls: [
                        "https://upload.wikimedia.org/wikipedia/commons/creanga.jpg",
                        "https://example.org/creanga-2.jpg",
                        "https://example.org/creanga-3.jpg",
                        "https://example.org/this-one-is-past-the-cap.jpg",
                ],
        ])
        assert response.statusCode == HttpStatus.OK

        final enriched = this.authorRepository.findByStrId(AUTHOR_STR_ID).first()
        assert enriched.bio == "Ion Creangă was a Romanian writer."
        assert enriched.bioSourceUrl.startsWith("https://en.wikipedia.org/")
        assert enriched.birthDate == "1837"
        assert enriched.deathDate == "1889"
        assert enriched.birthPlace == "Târgu Neamț"
        assert enriched.country == "Romania"
        assert enriched.writingLanguage == Languages.RO

        // the cap is 3 enrichment images per entity, urls only, no bytes
        final images = this.authorMediaAssociationRepository.findAllByAuthorPath(AUTHOR_STR_ID)
        assert images.size() == 3
        assert images.every { it.mediaRef.role == "enrichment" }
        assert images.every { it.mediaRef.url.startsWith("http") }
        assert !images.any { it.mediaRef.url.contains("past-the-cap") }

        // a crashed-and-rerun sweep must be a no-op, not a rewrite: the
        // worker has no way to know a manual correction happened in
        // between, so existing values always win
        post([
                authorStrId: AUTHOR_STR_ID,
                bio: "SOMETHING ELSE ENTIRELY",
                birthDate: "1900",
                imageUrls: ["https://example.org/another.jpg"],
        ])
        final rerun = this.authorRepository.findByStrId(AUTHOR_STR_ID).first()
        assert rerun.bio == "Ion Creangă was a Romanian writer."
        assert rerun.birthDate == "1837"
        final imagesAfterRerun = this.authorMediaAssociationRepository.findAllByAuthorPath(AUTHOR_STR_ID)
        assert imagesAfterRerun.size() == 3
    }

    @Test
    void opusUpdateFillsTheSummaryOnceAndKeepsExistingText() {
        final opus = opusOf(authorWith("Slavici, Ioan", "enrichment-ctrl-test-slavici"),
                "enrichment-ctrl-test-slavici.xml", "biblioteca", "Biblioteca")

        final response = post([
                opusId: opus.id,
                summary: "Biblioteca is a canonical short-story collection.",
                summarySourceUrl: "https://en.wikipedia.org/wiki/Amintiri_din_copilărie",
                imageUrls: [OPUS_IMAGE],
        ])
        assert response.statusCode == HttpStatus.OK

        final enriched = this.teiDivRepository.findById(opus.id).get()
        assert enriched.summary == "Biblioteca is a canonical short-story collection."
        assert enriched.summarySourceUrl.startsWith("https://en.wikipedia.org/")
        assert this.divMediaAssociationRepository.existsByDivPathAndMediaRefUrl(
                enriched.getCompletePath(), OPUS_IMAGE)
        assert this.divMediaAssociationRepository.existsByDivPathAndMediaRefRole(
                enriched.getCompletePath(), "enrichment")

        post([opusId: opus.id, summary: "SOMETHING ELSE ENTIRELY"])
        final rerun = this.teiDivRepository.findById(opus.id).get()
        assert rerun.summary == "Biblioteca is a canonical short-story collection."
    }

    @Test
    void unknownAuthorIsACleanNotFound() {
        assert post([authorStrId: "never-heard-of-them", bio: "nope"]).statusCode == HttpStatus.NOT_FOUND
    }
}
