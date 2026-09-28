package ro.editii.scriptorium.service

import editii.commons.xml.XpathTool
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ro.editii.scriptorium.InMemoryTeiRepo
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.cache.CacheMan
import ro.editii.scriptorium.cache.DiskCaches
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiElem
import ro.editii.scriptorium.tei.TeiRepo
import ro.editii.scriptorium.tei.TeifileParser

import static ro.editii.scriptorium.GTestUtil.p
import static ro.editii.scriptorium.GTestUtil.teiOf

/**
 * use this test for mainly the DivService#getByPath
 */
@SpringBootTest(
        properties = [
        "spring.datasource.url=jdbc:derby:memory:myDb;create=true",
        "spring.datasource.driver-class-name=org.apache.derby.iapi.jdbc.AutoloadedDriver",
        "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "cache.dir=/tmp/textbase-divservicetest-cache",
])
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import([ThisTestConfig.class, TestConfig.class])
class DivServiceTest {

    public static final String OP1 = "op1"

    @Autowired DivService divService
    @Autowired TeifileParser teifileParser
    @Autowired TeiRepo teiRepo
    @Autowired JdbcTemplate jdbcTemplate
    @PersistenceContext EntityManager entityManager

    @Autowired CacheMan cacheMan
    @Autowired DiskCaches diskCaches

    @BeforeAll
    void beforeAll() {
        this.cacheMan.clearAll()
        this.diskCaches.deleteAll()
        TestUtils.truncateAllTables(this.jdbcTemplate)
        ((InMemoryTeiRepo)this.teiRepo).map.clear()
        this.installFakeSampleJuliusCaesarInRepo()
    }


    @Test @Transactional
    void paragraphs() {
        final jc = this.divService.getByPath("shakespeare/julius_caesar")
        assert jc.opus

        final paras = this.divService.getParagraphs(jc)
        assert paras.size() > 0

        assert paras.name == ['head', 'p', 'head', 'p', 'head', 'p', 'p']
        assert this.divService.getParagraphs(jc, 0, 2) == paras[0..1]
        assert this.divService.getParagraphs(jc, 2, 3) == paras[2..4]
        assert this.divService.getParagraphs(jc, 5, 10) == paras[5..6]
        assert this.divService.getParagraphs(jc, paras.size(), 10).empty
        assert this.divService.getParagraphs(jc, 0, 0).empty

        final txtcontentOfParas = paras.nodeAsText.join(" ").trim().replaceAll(/\s+/, " ")
        assert txtcontentOfParas.contains('Julius Caesar')
        final txtContentOfXml = new XpathTool(this.teiRepo.getStreamForName(OP1), 'pulea')
                .xpath( editii.commons.xml.TeiDocument.XPATH_BODY + "/tei:div")
                .trim().replaceAll(/\s+/, " ")
        assert txtcontentOfParas == txtContentOfXml

        paras.xpath.each {assert it != null }
        paras.completePath.each {
            assert ! it.endsWith("null");
            Integer.parseInt(it[-1]) // assert last char's a number
        }

    }

    @Test
    void inexistantUrl() {
        assert this.divService.getByPath("shakespeare/julius_caesar") != null
        assert this.divService.getByPath("shakespeare/julius_caesar/act_i") != null

        try {
            this.divService.getByPath("inexistant/julius_caesar")
            Assertions.fail('should fail')
        } catch (ResponseStatusException e) {
            assert e.statusCode.value() == 404
        }

        try {
            this.divService.getByPath("shakespeare/inexistant")
            Assertions.fail('should fail')
        } catch (ResponseStatusException e) {
            assert e.statusCode.value() == 404
        }

        try {
            this.divService.getByPath("shakespeare/julius_caesar/inexistant")
            Assertions.fail('should fail')
        } catch (ResponseStatusException e) {
            assert e.statusCode.value() == 404
        }

    }



    @Test @Transactional
    void testChildren() {
            def jc =  this.divService.getByPath("shakespeare/julius_caesar")

            assert jc != null

            jc = this.entityManager.merge(jc)
            jc.teiRepo = this.teiRepo
            assert jc.dbChildren.size() == 1 // 1 db TeiDiv
            assert jc.childrenElements.size() == 3 // 3 elements

            final jc_div1 = jc.dbChildren[0]
            assert jc_div1 != null
            assert jc_div1.dbChildren.size() == 1

    }

    @Test
    void getByPath() {

        final jc =  this.divService.getByPath("shakespeare/julius_caesar")
        final childrenElements = jc.childrenElements
        assert jc instanceof TeiDiv
        final jc_1 =  this.divService.getByPath("shakespeare/julius_caesar/_1")
        assert jc_1 instanceof TeiElem
        assert jc_1.nodeAsText == 'Julius Caesar'

        assert jc_n(1).nth == 1
        assert jc_n(2).nth == 2

        final jc1 =  this.divService.getByPath("shakespeare/julius_caesar")
        assert jc1 instanceof TeiDiv
        assert jc1.childrenElements.size() == 3


        (1.. childrenElements.size()).each {
            final elem = jc_n(it)
            assert elem != null
            assert elem.nodeAsXmlString.length() > 0
            assert elem.nodeAsText.length() > 0
        }

        jc_n(1).with {
            assert nodeAsText == 'Julius Caesar'

            assert it instanceof TeiElem
            assert it.name == 'head'
            assert ! (it instanceof TeiDiv)
        }

        jc_n(2).with {
            assert  nodeAsText == 'Personae'
            assert it instanceof TeiElem
            assert it.name == 'p'
            assert ! (it instanceof TeiDiv)
        }

        jc_n(3).with { elem ->
            assert elem.name == 'div'
            assert elem instanceof TeiDiv
            assert elem.urlFragment == 'act_i' // make sure it comes from db
            assert elem.head == 'Act I'
            assert elem.teiFile != null
            assert elem.teiRepo != null
        }

        jc_n([3, 1]).with { elem ->
            assert elem.name == 'head'
            p elem.nodeAsText
            assert elem.nodeAsText == 'Act I'
        }

        try {
            // there should be no such element (inside the head there is only text)
            jc_n([3, 1, 1])
            Assertions.fail('no go')
        } catch (org.springframework.data.rest.webmvc.ResourceNotFoundException e) {
            assert e.message == 'no child element for nth 1 (starting at 1)'
        }

        jc_n([3, 2]).with { elem ->
            assert elem.name == 'p'
            assert elem.nodeAsText == 'Hei Julius'
            assert elem.nodeAsXmlString.contains('Hei <i>Julius</i>')
        }

        jc_n([3, 2, 1]).with { elem ->
            assert elem.name == 'i'
            assert elem.nodeAsText == 'Julius'
            assert elem.nodeAsXmlString.contains('Julius</i>')
        }

        jc_n([3, 3]).with { elem ->
            assert elem instanceof TeiDiv
            assert elem.parent instanceof TeiDiv
            assert elem.name == 'div'
            assert elem.nodeAsText.contains('Scene I')
            assert elem.nodeAsXmlString.contains('Scene I')
        }

        jc_n([3, 3, 1]).with { elem ->
            assert elem.name == 'head'
            assert elem.nodeAsText == 'Scene I'
        }

        jc_n([3, 3, 2]).with { elem ->
            assert elem.name == 'p'
            assert elem.nodeAsText == 'foaie foarte verde'
        }

        jc_n([3, 3, 2, 1]).with { elem ->
            assert elem.name == 'span'
            assert elem.nodeAsText == 'foarte'
        }
    }

    protected String installFakeSampleJuliusCaesarInRepo() {
        final repo = this.teiRepo
        final tei1 = teiOf('William Shakespeare', """
            <div>
                <head>Julius Caesar</head>
                <p>Personae</p>
                <div>
                    <head>Act I</head>
                    <p>Hei <i>Julius</i></p>
                    <div>
                        <head>Scene I</head>
                        <p>foaie <span>foarte</span> verde</p>
                        <p>foaie lata</p>
                    </div>
                </div>
            </div>
        """)
        repo[OP1] = tei1

        final divs = this.teifileParser.parse(OP1, tei1, Languages.RO)
        divs
    }

    TeiElem jc_n(int  i) { jc_n([i]) }

    TeiElem jc_n(List list) {
        final path = "shakespeare/julius_caesar/" + list.collect {"_$it"}.join("/")
        this.divService.getByPath(path)
    }

    @TestConfiguration
    static class ThisTestConfig {

        @Bean @Primary
        TeiRepo teiRepo() {
            return new InMemoryTeiRepo()
        }
    }
}
