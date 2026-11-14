package ro.editii.scriptorium.cache

import org.apache.commons.io.output.NullWriter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.service.AdminService
import ro.editii.scriptorium.service.DivService
import ro.editii.scriptorium.service.ImportFailures
import ro.editii.scriptorium.service.LanguageDetectionService
import ro.editii.scriptorium.service.TeiFileDbService
import ro.editii.scriptorium.tei.TeiDirRepoImpl
import ro.editii.scriptorium.tei.TeiRepo

import java.nio.file.Files
import java.nio.file.Path

/**
 * Imports and the caches, through the real AdminService / TeiFileDbService /
 * TeifileParser on H2 (as RelocationReimportTest): a special author imports
 * again after its row was deleted; an import builds its work's TOC and leaves
 * the other works' caches alone; a broken file is not retried every sweep.
 * docs/caching-plan.md
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:importCachesTestDb;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create"
])
@SpringBootTest(classes = [TestConfig.class, ImportCachesTest.RepoConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
class ImportCachesTest {

    static final Path REPO = Files.createTempDirectory('import-caches-repo')

    @TestConfiguration
    static class RepoConfig {
        @Bean
        TeiRepo teiRepo() { new TeiDirRepoImpl(REPO.toString()) }

        @Bean @Primary
        TextbaseEventsPublisher textbaseEventsPublisher() {
            [signalNewOpusImported: {}, signalOpusReimported: {}, signalOpusRemoved: {}] as TextbaseEventsPublisher
        }

        @Bean @Primary
        LanguageDetectionService languageDetectionService() {
            new LanguageDetectionService() {
                @Override Optional<Languages> detect(String rawTeiXml) { Optional.of(Languages.FR) }
            }
        }
    }

    @Autowired AdminService adminService
    @Autowired TeiFileDbService teiFileDbService
    @Autowired DivService divService
    @Autowired ImportFailures importFailures
    @Autowired TeiDivRepository teiDivRepository
    @Autowired TeiFileRepository teiFileRepository
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired PlatformTransactionManager transactionManager

    static String tei(String author, String title, List<String> chapters) {
        """<TEI xmlns="http://www.tei-c.org/ns/1.0">
             <teiHeader><fileDesc><titleStmt><title>$title</title><author>$author</author></titleStmt></fileDesc></teiHeader>
             <text><body><div><head>$title</head>${chapters.collect { "<div><head>$it</head><p>Le texte de $it.</p></div>" }.join('')}</div></body></text></TEI>"""
    }

    @BeforeEach
    void clean() {
        TestUtils.truncateAllTables(jdbcTemplate)
        REPO.toFile().listFiles()*.delete()
        importFailures.all().each { importFailures.succeeded(it.filename()) }
    }

    void write(String file, String content) { Files.writeString(REPO.resolve(file), content) }

    void reimport(String file) { adminService.reimportFile(file, new NullWriter()) }

    List<Long> opusIds(String file) {
        new TransactionTemplate(transactionManager).execute {
            teiFileRepository.getByFilename(file).map { tf -> teiDivRepository.getOperaForTeiFileId(tf.id)*.id }.orElse([])
        } as List<Long>
    }

    int authorRows(String strId) {
        jdbcTemplate.queryForObject('select count(*) from author where str_id = ?', Integer, strId)
    }

    @Test
    void aSpecialAuthorImportsAgainAfterItsRowWasDeleted() {
        write('pline.xml', tei('Pline le Jeune', 'Lettres', ['Livre I', 'Livre II']))
        reimport('pline.xml')
        assert authorRows('pline_le_jeune') == 1

        // its only file deleted: the author row goes too - and the shared
        // special-author instance used to keep that row's id, so the next
        // import merged a row that no longer existed
        new TransactionTemplate(transactionManager).executeWithoutResult { teiFileDbService.deleteTeiFile('pline.xml') }
        assert authorRows('pline_le_jeune') == 0

        reimport('pline.xml')
        assert authorRows('pline_le_jeune') == 1
        assert opusIds('pline.xml').size() == 1
    }

    @Test
    void anImportBuildsItsTocAndLeavesTheOtherWorksCaches() {
        write('pline.xml', tei('Pline le Jeune', 'Lettres', ['Livre I', 'Livre II']))
        write('hamlet.xml', tei('Ion Scriitorul', 'Tragedia', ['Actul I']))
        reimport('pline.xml')
        reimport('hamlet.xml')
        def pline = opusIds('pline.xml')[0], hamlet = opusIds('hamlet.xml')[0]
        assert divService.hasToc(pline) && divService.hasToc(hamlet) // built at import

        write('hamlet.xml', tei('Ion Scriitorul', 'Tragedia', ['Actul I', 'Actul II']))
        reimport('hamlet.xml')
        def hamletNow = opusIds('hamlet.xml')[0]
        assert hamletNow != hamlet
        assert !divService.hasToc(hamlet) && divService.hasToc(hamletNow)
        assert divService.getToc(hamletNow).size() == 3
        assert divService.hasToc(pline) // untouched
    }

    @Test
    void aBrokenFileIsTriedOnceNotEverySweep() {
        write('pline.xml', tei('Pline le Jeune', 'Lettres', ['Livre I']))
        write('broken.xml', '<TEI xmlns="http://www.tei-c.org/ns/1.0"><text><body><div><head>Pas fini')
        def first = new StringWriter(), second = new StringWriter()
        adminService.reimportFresherTeis(first)
        adminService.reimportFresherTeis(second)

        // the sweep names files from the repository root: /broken.xml
        assert first.toString().contains('will import /broken.xml') && first.toString().contains('failed to import /broken.xml')
        assert !second.toString().contains('broken.xml') // held back
        assert importFailures.all()*.filename() == ['/broken.xml'] && importFailures.all()[0].attempts() == 1
        assert opusIds('/pline.xml').size() == 1 // the good file imported regardless

        write('broken.xml', tei('Ion Scriitorul', 'Reparat', ['Actul I'])) // fixed: tried again at once
        REPO.resolve('broken.xml').toFile().setLastModified(System.currentTimeMillis() + 5000)
        adminService.reimportFresherTeis(new StringWriter())
        assert importFailures.all().isEmpty() && opusIds('/broken.xml').size() == 1
    }
}
