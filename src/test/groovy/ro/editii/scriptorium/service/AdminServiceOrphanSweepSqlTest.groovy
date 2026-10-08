package ro.editii.scriptorium.service

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.Util
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.model.TeiDiv

/**
 * Runs the orphan sweep's raw SQL against a schema Hibernate's ddl-auto
 * actually generated - the stored identifier names are what broke it
 * (a quoted "teiFile_id" that the PostgreSQL schema does not have, failing
 * every scheduler cycle). Mocked JdbcTemplate tests cannot see that.
 *
 * In-memory Derby by default; ORPHAN_SWEEP_TEST_DB_URL points it at a
 * throwaway PostgreSQL (the runtime engine) - never at a shared database:
 * ddl-auto=create drops and recreates the tables.
 */
@TestPropertySource(properties = [
        'spring.datasource.url=${ORPHAN_SWEEP_TEST_DB_URL:jdbc:derby:memory:orphanSweep;create=true}',
        // embedded Derby needs its own driver named (the jdbc:derby: default
        // is the network client, which rejects memory URLs); a PostgreSQL
        // URL sets ORPHAN_SWEEP_TEST_DB_DRIVER=org.postgresql.Driver
        'spring.datasource.driver-class-name=${ORPHAN_SWEEP_TEST_DB_DRIVER:org.apache.derby.iapi.jdbc.AutoloadedDriver}',
        'spring.jpa.hibernate.ddl-auto=create',
        'spring.flyway.enabled=false',
        'spring.main.allow-bean-definition-overriding=true'])
@SpringBootTest(classes = [TestConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
class AdminServiceOrphanSweepSqlTest {

    @Autowired AdminService adminService
    @Autowired TeiDivRepository teiDivRepository
    @Autowired JdbcTemplate jdbcTemplate

    @Test
    void sweepsAnOpusWhoseTeiFileIsGone() {
        given: 'a root div with no tei_file - what out-of-band tei_file deletion leaves behind'
        def orphan = new TeiDiv()
        orphan.head = 'orphaned opus'
        orphan.urlFragment = 'orphaned_opus'
        orphan = teiDivRepository.saveAndFlush(orphan)
        assert teiDivRepository.findById(orphan.id).present

        when:
        def log = new StringWriter()
        adminService.pruneOrphanedElems(log)

        then: 'the SQL resolves against the generated schema and the orphan is gone'
        assert !teiDivRepository.findById(orphan.id).present
        assert jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ${Util.TEI_ELEM}", Integer) == 0
    }
}
