package ro.editii.scriptorium.tei

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.model.Languages

import static ro.editii.scriptorium.GTestUtil.countTableRows

/**
 * Reproduces the dev-deployment failure where the autoimport sweep hits
 * "ERROR 23505: duplicate key ... on 'AUTHOR'": the corpus's TEI files
 * spell the author "Sigmund Freud" (first last), Author stores the
 * recomposed "Freud,Sigmund" as originalNameInTeiFile, and a re-import
 * into a populated DB must find and REUSE the existing author row by
 * that key instead of inserting a colliding one.
 */
@TestPropertySource(properties=[
        "spring.datasource.url=jdbc:derby:memory:myDb2;create=true",
        "spring.datasource.driver-class-name=org.apache.derby.iapi.jdbc.AutoloadedDriver",
        "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
        "spring.jpa.hibernate.ddl-auto = create",
        "spring.main.allow-bean-definition-overriding=true"])
@SpringBootTest(classes = [ TestConfig.class ])
@EnableAutoConfiguration(exclude= [KafkaAutoConfiguration.class])
class TeifileParserAuthorReuseTest {

    @Autowired JdbcTemplate jdbcTemplate
    @Autowired TeifileParser teifileParser

    final static String TEI_TMPL = '''<TEI xmlns="http://www.tei-c.org/ns/1.0">
           <teiHeader>
              <fileDesc>
                 <titleStmt>
                    <title>%s</title>
                    <author>Sigmund Freud</author>
                 </titleStmt>
              </fileDesc>
           </teiHeader>
           <text>
              <body>
                 <div><head>%s</head>
                 <p>content content content</p>
                 </div>
              </body>
           </text>
        </TEI>'''

    @BeforeEach
    void before() {
        this.jdbcTemplate.update("DELETE FROM tei_file_authors")
        this.jdbcTemplate.update("DELETE FROM author")
        this.jdbcTemplate.update("DELETE FROM ${TestUtils.TEI_ELEM}")
        this.jdbcTemplate.update("DELETE FROM tei_file")
    }

    private int countAuthors() {
        countTableRows(this.jdbcTemplate, "author")
    }

    @Test
    void 'a second file with the same author reuses the existing author row'() {
        this.teifileParser.parse("f1.xml", String.format(TEI_TMPL, "Erste Arbeit", "Erste Arbeit"), Languages.DE)
        assert countAuthors() == 1
        assert this.jdbcTemplate.queryForList(
                'select ORIGINAL_NAME_IN_TEI_FILE from author', String.class).first() == 'Freud,Sigmund'

        this.teifileParser.parse("f2.xml", String.format(TEI_TMPL, "Zweite Arbeit", "Zweite Arbeit"), Languages.DE)

        assert countAuthors() == 1 : "the second import must reuse the existing author, not insert a colliding one"
    }

    @Test
    void 'a re-import of the same file reuses the existing author row'() {
        this.teifileParser.parse("f1.xml", String.format(TEI_TMPL, "Erste Arbeit", "Erste Arbeit"), Languages.DE)
        assert countAuthors() == 1

        // forceReimport's path: delete the file's rows, then import again
        this.jdbcTemplate.update("DELETE FROM ${TestUtils.TEI_ELEM} where tei_file_id in (select id from tei_file where filename = 'f1.xml')")
        this.jdbcTemplate.update("DELETE FROM tei_file_authors where tei_file_id in (select id from tei_file where filename = 'f1.xml')")
        this.jdbcTemplate.update("DELETE FROM tei_file where filename = 'f1.xml'")

        this.teifileParser.parse("f1.xml", String.format(TEI_TMPL, "Erste Arbeit", "Erste Arbeit"), Languages.DE)

        assert countAuthors() == 1 : "a re-import must reuse the existing author, not insert a colliding one"
    }
}
