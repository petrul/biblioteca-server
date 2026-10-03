package ro.editii.scriptorium.rest


import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.Util
import ro.editii.scriptorium.client.BibliotecaClient
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.service.AdminService

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [ TestConfig.class ])
@TestPropertySource(properties=[
        "spring.datasource.url=jdbc:derby:memory:myDb;create=true",
        "spring.datasource.driver-class-name=org.apache.derby.iapi.jdbc.AutoloadedDriver",
        "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create"
])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DivRestControllerTest {

    @LocalServerPort int localPort

    @Autowired AdminService adminService
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired TeiDivRepository teiDivRepository
    @Autowired BibliotecaClient tbc

    @Test
    void paragraphs() {

        final opi = this.teiDivRepository.findAll().findAll {
            it.head == 'Poezii populare ale românilor'
            && it.isOpus()
        }
        assert opi.size() > 0

        final firstOpId = opi.first().id

        opi.each {
            final elems = tbc.get_api_divs_id_paras(it.id)
            final Set paths = elems.path // paths should be unique
            elems.text.each { assert it == null } // no text
            assert elems.length > 0
            assert elems.length == paths.size()
        }

        final firstOpParas = tbc.get_api_divs_id_paras(firstOpId, 0, Integer.MAX_VALUE)
        assert firstOpParas.size() == 560
        firstOpParas.each {
            assert it != null;
            assert it.path != null
            assert !it.path.empty
            assert !it.url.empty

            // text was not explicitly asked by query param
            assert it.text == null
            assert it.text_sha256 == null
        }
        // check paging works properly
        final firstPage = tbc.get_api_divs_id_paras(firstOpId, 0, 2)
        assert firstPage.size() == 2
        assert firstPage == firstOpParas[0..1]

        assert tbc.get_api_divs_id_paras(firstOpId, 1, 7) == firstOpParas[7..13]
        assert tbc.get_api_divs_id_paras(firstOpId, 5, 100) == firstOpParas[500..<560]
    }

    @Test
    void paragraphsWithText() {
        final firstOpus = this.teiDivRepository.findAll().find {it.isOpus() }
        final firstOpId  = firstOpus.id

        final elems = this.tbc.get_api_divs_id_paras(firstOpId, 0, Integer.MAX_VALUE, true)
        assert elems.length > 10
        final representativeElems = [elems.first(), elems[elems.length.intdiv(2)], elems.last()]
        representativeElems.each {
            assert ! it.text.empty
            assert ! it.url.empty
            assert ! it.text_sha256.empty

            // reget the text using a regular GET to the path
            final text2 = this.tbc.getTextPlain(it.path)
            assert it.text == text2

            assert Util.sha256Hex(it.text) == it.text_sha256
        }
    }

    @Test
    void getTeiDivByIdAndByPath() {
        final tbc = new RestApiClient(url('/'))

        final divIds = teiDivRepository.findAll().stream()
                .map(it -> it.getId()).toList()

        assert divIds.size() > 2
        final representativeIds = [divIds.first(), divIds[divIds.size().intdiv(2)], divIds.last()]
        final divs = representativeIds.collect {tbc.get_div_id(it)}

        assert ! divs.empty

        final elemsByPath = divs.collect {tbc.get_div_path(it.path)}

        assert divs.size() == elemsByPath.size()
        assert divs.id == elemsByPath.id
        assert divs.path == elemsByPath.path
        assert divs.urlFragment == elemsByPath.urlFragment
        assert divs.xpath == elemsByPath.xpath
    }

    @Test
    void missingOpusRemainsNotFound() {
        def connection = new URL(url('/api/divs?path=missing/opus')).openConnection()
        connection.requestMethod = 'GET'

        assert connection.responseCode == 404
        assert connection.errorStream.text.contains('no opus for [missing/opus]')
    }

    def p(args) { println(args) }

    def url(String path) {
        if (path.startsWith('/'))
            path = path.substring(1)

        return "http://localhost:${this.localPort}/$path"
    }

    @Test
    void findOpera() {
        // this is used by the vectorizer so it'd better work
        final opera = this.tbc.get_api_drest_teiDivs_search_findOpera()
        assert  opera.size() > 0
        assert  opera.head.contains('Lăcrimioare')
    }

    @BeforeAll
    void beforeAll() {
        adminService.reimportAllTeis(new OutputStreamWriter(System.out))
        assert countTableRows("author") > 0
        assert countTableRows("tei_file_authors") > 0
        assert countTableRows(TestUtils.TEI_ELEM) > 0
    }

    def countTableRows(tableName) {
        return this.jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class)
    }

}
