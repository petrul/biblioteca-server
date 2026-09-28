package ro.editii.scriptorium.tei


import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TextbaseConfig
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiFile
import ro.editii.scriptorium.service.TeiFileDbService

import javax.sql.DataSource
import jakarta.transaction.Transactional

@TestPropertySource(properties=[
        "spring.datasource.url=jdbc:derby:memory:myDb;create=true",
        "spring.datasource.driver-class-name=org.apache.derby.iapi.jdbc.AutoloadedDriver",
        "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
        "spring.jpa.hibernate.ddl-auto = update",
        "spring.main.allow-bean-definition-overriding=true"])
@SpringBootTest(classes = [ TestConfig.class ])
@EnableAutoConfiguration(exclude = [ KafkaAutoConfiguration.class] )
class TeifileParserITest {

    @Autowired TeiFileRepository teiFileRepository
    @Autowired TeiDivRepository teiDivRepository
    @Autowired TeiFileDbService teiRepoService
    @Autowired TeiRepo teiRepo
    @Autowired DataSource dataSource
    @Autowired TeifileParser parser

    @MockitoBean TextbaseEventsPublisher eventsPublisher
    @MockitoBean TextbaseConfig textbaseConfig

    @Test
    @Transactional
    void testParserForAlecsandriScrieri() {
        println "jdbc url: " + this.dataSource.connection.metaData.URL;

        def alecsandri_xml = "testrepo/ro/Alecsandri-Scrieri.xml"

        this.teiRepoService.deleteTeiFile(alecsandri_xml)

        assert ! this.teiFileRepository.getByFilename(alecsandri_xml).isPresent()

        final lang = Languages.PT
        parser.parse(alecsandri_xml,
                this.class.classLoader.getResourceAsStream(alecsandri_xml),
                lang)

        assert this.teiFileRepository.getByFilename(alecsandri_xml).present

        final TeiFile teifile = this.teiFileRepository.getByFilename(alecsandri_xml).get()
        final List<TeiDiv> divs = this.teiDivRepository.findByTeiFile(teifile)

        divs.each {
            assert  ! it.head.blank
            assert it.lang == lang
        }

//        println divs.collect { it.head }.join("\n")
        println divs.collect { it.urlFragment }.join("\n")

        assert divs.findAll { it.head == 'Manifeste și amintiri politice' }.size() == 1

        final TeiDiv despot_voda = divs.find { it.urlFragment == "despot_voda" }

        assert despot_voda != null

        final List subdivurileLuiDespot = despot_voda.getDbChildren()
        assert ! subdivurileLuiDespot.head.empty

        // assert PERSONAJELE is directly under Despot Voda, not under the empty div it is really in the xml
        assert subdivurileLuiDespot.find { it.head.trim() == "PERSONAJELE"} != null

    }


    @Test
    void testServiceImport() {
        final listing = this.teiRepo.list()
//        println listing
        def alecs_tei = listing.findAll { it.contains( 'Alecsandri-Scrieri.xml')}.first()

        println "*" * 80
        this.teiRepoService.deleteTeiFile(alecs_tei)
        println "*" * 80
        this.teiRepoService.importTeiFile(alecs_tei, true)

        this.teiRepoService.deleteTeiFile(alecs_tei)
        println "*" * 80
        this.teiRepoService.importTeiFile(alecs_tei, true)
    }
}
