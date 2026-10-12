package ro.editii.scriptorium.tei

import groovy.util.logging.Log
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.Util
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.model.TeiFile

@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:myDb;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.main.allow-bean-definition-overriding=true"])
@SpringBootTest
@ContextConfiguration(classes = TestConfig.class)
@Log
class TeiRepoTest {

    @Autowired
    TeiRepo teiRepo

    @Test
    void test1() {
        List listing = teiRepo.list()
        p listing
        assert listing != null
        assert listing.size() > 3
        assert listing
                .findAll { it.contains("Cantemir-Descrierea_Moldovei.xml")}
                .size() == 1

        println teiRepo.list()
    }

    @MockitoBean
    private TeiFileRepository teiFileRepository

    @Test
    void test2() {

        TeiFile teiFile = new TeiFile()
        Mockito.when(teiFileRepository.save(teiFile)).then {
            println teiFile
        }

        final resource = this.getClass().getResource("teirepo")
        final repo = new TeiDirRepoImpl(Util.urlToFileString(resource))
        assert 1 == repo.list().size()
    }


    def static p(args) { println(args) }
}