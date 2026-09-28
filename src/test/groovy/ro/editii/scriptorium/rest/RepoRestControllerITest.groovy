package ro.editii.scriptorium.rest

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.dto.AuthorDto
import ro.editii.scriptorium.service.AdminService

@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:myDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.main.allow-bean-definition-overriding=true"])
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [ TestConfig.class ])
@AutoConfigureTestRestTemplate
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepoRestControllerITest {

    @LocalServerPort private int port

    @Autowired private TestRestTemplate restTemplate
    @Autowired AdminService adminService

    @Autowired
    JdbcTemplate jdbcTemplate

    def countTableRows(tableName) {
        return this.jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class)
    }



    void truncateAllTables() {
        // DELETE FROM, not TRUNCATE + SET FOREIGN_KEY_CHECKS - see
        // AdminService.destroyAllExistingAndReimportAllTeis's own comment.
        this.jdbcTemplate.update("DELETE FROM tei_file_authors")
        this.jdbcTemplate.update("DELETE FROM author");
        this.jdbcTemplate.update("DELETE FROM " + TestUtils.TEI_ELEM);
        this.jdbcTemplate.update("DELETE FROM tei_file");

        assert countTableRows("author") == 0
        assert countTableRows("tei_file_authors") == 0
        assert countTableRows(TestUtils.TEI_ELEM) == 0
    }


    @BeforeAll
    void beforeAll() {
        this.truncateAllTables()

        adminService.reimportAllTeis(new OutputStreamWriter(System.out))

        assert countTableRows("author") > 0
        assert countTableRows("tei_file_authors") > 0
        assert countTableRows(TestUtils.TEI_ELEM) > 0

    }

    @AfterAll
    void afterTransaction() {
        this.truncateAllTables();
    }

    @Test
    void getAuthors() {

        final url = "http://localhost:" + port + "/api/authors/"
        final List<AuthorDto> authors = this.restTemplate.getForEntity(url, List<AuthorDto>.class).body
        assert  authors != null
        assert authors.size() > 0
        p authors
        authors.each { p it }
        final ids = authors.collect { it -> it.strId }
        ['alecsandri', 'creanga', 'cantemir'].each {
            assert ids.contains(it)
        }
    }

    @Test
    void getAuthor() {
        final alecsandri = 'alecsandri'
        def url = "http://localhost:" + port + "/api/authors/$alecsandri"
        AuthorDto author = this.restTemplate.getForEntity(url, AuthorDto.class).body
        p author
        assert author != null
        assert author.strId == alecsandri
        assert author.opera != null
        assert author.opera.size() > 0

//        p author.opera
        assert author.opera.length > 0
        author.opera.each {
            assert it.head != null ;
            assert it.head.size() > 0
            assert it.urlFragment != null
            assert it.urlFragment.size() > 0
            assert it.path != null
            assert it.path.size() > 0
        }

        final operaHeads = author.opera.collect{ it.head}
        assert ! operaHeads.empty
    }

    def p(args) {
        println(args)
    }
}