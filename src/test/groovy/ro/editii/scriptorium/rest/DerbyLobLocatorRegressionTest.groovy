package ro.editii.scriptorium.rest

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.junit.jupiter.api.Tag
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiFile

/**
 * Derby exposes CLOBs as transaction-bound locators.  A detached Author with
 * a bio therefore used to fail while AuthorDto.from() was serializing it.
 * Keep this as an HTTP-level regression test: the repository transaction has
 * ended before the controller is invoked, just as it has for a real request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TestConfig.class])
@AutoConfigureTestRestTemplate
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:derby:memory:derbyLobLocatorRegression;create=true",
        "spring.datasource.driver-class-name=org.apache.derby.iapi.jdbc.AutoloadedDriver",
        "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.main.allow-bean-definition-overriding=true",
        "lucene.autoindex.enabled=false"
])
@DirtiesContext
@Tag("integration-test")
class DerbyLobLocatorRegressionTest {

    @Autowired TestRestTemplate restTemplate
    @Autowired AuthorRepository authorRepository
    @Autowired TeiFileRepository teiFileRepository
    @Autowired TeiDivRepository teiDivRepository

    @org.junit.jupiter.api.Test
    void authorBioCanBeReadAfterRepositoryTransactionHasClosed() {
        def author = Author.newFromOriginalNameInTeiFile("Derby, Lob")
        author.strId = "derby-lob-locator-regression"
        author.bio = "A biography long enough to be stored as a Derby CLOB."
        authorRepository.saveAndFlush(author)

        def response = restTemplate.getForEntity("/api/authors/", String)

        assert response.statusCode == HttpStatus.OK
        assert response.body.contains("A biography long enough to be stored as a Derby CLOB.")
    }

    @org.junit.jupiter.api.Test
    void springDataRestFindOperaCanSerializeAnOpusWithAuthorLob() {
        def author = Author.newFromOriginalNameInTeiFile("DREST, Lob")
        author.strId = "drest-lob-locator-regression"
        author.bio = "A biography that must not be read through an invalid Derby CLOB locator."
        authorRepository.saveAndFlush(author)

        def teiFile = new TeiFile()
        teiFile.filename = "drest-lob-locator-regression.xml"
        teiFile.language = Languages.EN
        teiFile.authors = [author]
        teiFileRepository.saveAndFlush(teiFile)

        def opus = new TeiDiv()
        opus.teiFile = teiFile
        opus.xpath = "/tei:TEI/tei:text/tei:body/tei:div[1]"
        opus.urlFragment = "drest-lob-work"
        opus.head = "DREST CLOB regression work"
        opus.lang = Languages.EN
        opus.nth = 1
        teiDivRepository.saveAndFlush(opus)

        // This is the endpoint that previously triggered "Unable to access lob
        // stream" when Spring Data REST serialized the detached entity graph.
        def response = restTemplate.getForEntity(
                "/api/drest/teiDivs/search/findOpera?page=0&size=20", String)

        assert response.statusCode == HttpStatus.OK
        assert response.body.contains("DREST CLOB regression work")
    }

    @org.junit.jupiter.api.Test
    void featuredSystemCollectionReturnsOnlyAllowListedWorks() {
        def author = Author.newFromOriginalNameInTeiFile("Dulfu, Petre")
        author.strId = "dulfu"
        author.displayName = "Petre Dulfu"
        authorRepository.saveAndFlush(author)

        def teiFile = new TeiFile()
        teiFile.filename = "featured-pacala-regression.xml"
        teiFile.language = Languages.RO
        teiFile.authors = [author]
        teiFileRepository.saveAndFlush(teiFile)

        def opus = new TeiDiv()
        opus.teiFile = teiFile
        opus.xpath = "/tei:TEI/tei:text/tei:body/tei:div[1]"
        opus.urlFragment = "ispravile_lui_pacala"
        opus.head = "Isprăvile lui Păcală"
        opus.lang = Languages.RO
        opus.nth = 1
        teiDivRepository.saveAndFlush(opus)

        def response = restTemplate.getForEntity("/api/collections/system/featured", String)

        assert response.statusCode == HttpStatus.OK
        assert response.body.contains("Isprăvile lui Păcală")
    }
}
