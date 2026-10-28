package ro.editii.scriptorium.model

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.Util
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.toc.Toc

/**
 * A division's children - and so its table of contents - come back in
 * document order even after some child rows were updated. PostgreSQL
 * returns unordered rows in storage order, and an UPDATE moves a row to the
 * end of the table: without TeiElem.dbChildren's @OrderBy, delavrancea's
 * "Apus de soare" listed Act II's scenes VI-XII before scenes I-V.
 *
 * In-memory H2 by default (which happens to return primary-key order, so
 * there it pins the expected order only); CHILDREN_ORDER_TEST_DB_URL points
 * it at a throwaway PostgreSQL, where the reordering really happens - never
 * at a shared database: ddl-auto=create drops and recreates the tables.
 */
@TestPropertySource(properties = [
        'spring.datasource.url=${CHILDREN_ORDER_TEST_DB_URL:jdbc:h2:mem:childrenOrder;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}',
        // a PostgreSQL URL sets CHILDREN_ORDER_TEST_DB_DRIVER=org.postgresql.Driver
        'spring.datasource.driver-class-name=${CHILDREN_ORDER_TEST_DB_DRIVER:org.h2.Driver}',
        'spring.jpa.hibernate.ddl-auto=create',
        'spring.flyway.enabled=false',
        'spring.main.allow-bean-definition-overriding=true'])
@SpringBootTest(classes = [TestConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
class TeiElemChildrenOrderSqlTest {

    @Autowired TeiDivRepository teiDivRepository
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired PlatformTransactionManager transactionManager

    @Test
    void childrenAndTocKeepDocumentOrderAfterChildRowsAreUpdated() {
        given: 'an act with five scenes, saved in document order'
        def act = new TeiDiv()
        act.head = 'Actul II'
        act.urlFragment = 'actul_ii'
        act = teiDivRepository.saveAndFlush(act)
        final scenes = ['Scena I', 'Scena II', 'Scena III', 'Scena IV', 'Scena V']
        final ids = scenes.collect { head ->
            def scene = new TeiDiv()
            scene.head = head
            scene.urlFragment = head.toLowerCase().replace(' ', '_')
            scene.parent = act
            teiDivRepository.saveAndFlush(scene).id
        }

        and: 'the first two scenes updated afterwards (on PostgreSQL their rows move to the end of the table)'
        jdbcTemplate.update("UPDATE ${Util.TEI_ELEM} SET word_size = word_size + 1 WHERE id IN (?, ?)".toString(), ids[0], ids[1])

        expect: 'a fresh load lists the children, and the TOC, in document order'
        final actId = act.id
        new TransactionTemplate(transactionManager).execute {
            final loaded = teiDivRepository.findById(actId).get()
            assert loaded.dbChildren*.head == scenes
            assert new Toc(loaded).collect { it.head } == ['Actul II'] + scenes
        }
    }
}
