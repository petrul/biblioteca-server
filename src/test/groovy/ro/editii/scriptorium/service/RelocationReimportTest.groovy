package ro.editii.scriptorium.service

import groovy.json.JsonSlurper
import org.apache.commons.io.output.NullWriter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.dao.AppUserRepository
import ro.editii.scriptorium.dao.ReadingProgressRepository
import ro.editii.scriptorium.dao.RelocationRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.model.AppUser
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.ReadingProgress
import ro.editii.scriptorium.model.Relocation
import ro.editii.scriptorium.tei.TeiDirRepoImpl
import ro.editii.scriptorium.tei.TeiRepo

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/**
 * Relocations end to end: a TEI file is imported, edited and reimported
 * through the real AdminService/TeiFileDbService/TeifileParser on H2, and the
 * divisions whose paths changed must answer their old URLs with a 301 to the
 * same division - pages (extension and query kept), /api/divs?path= and
 * /api/relocations - while live paths are never redirected, reverts reclaim
 * their paths, chains collapse and saved reading progress follows.
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:relocationReimportTestDb;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create"
])
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TestConfig.class, RelocationReimportTest.EditableRepoConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
class RelocationReimportTest {

    static final Path REPO = Files.createTempDirectory('relocation-reimport-repo')
    static final String FILE = 'tragedia.xml'

    @TestConfiguration
    static class EditableRepoConfig {
        @Bean
        TeiRepo teiRepo() { new TeiDirRepoImpl(REPO.toString()) }

        // Native Groovy doubles: no Kafka, and no Lingua n-gram models - GBs
        // of heap at first detection (see AdminServiceDestroyAllExistingAndReimportAllTeisTest)
        @Bean @Primary
        TextbaseEventsPublisher textbaseEventsPublisher() {
            [signalNewOpusImported: {}, signalOpusReimported: {}, signalOpusRemoved: {}] as TextbaseEventsPublisher
        }

        @Bean @Primary
        LanguageDetectionService languageDetectionService() {
            new LanguageDetectionService() {
                @Override Optional<Languages> detect(String rawTeiXml) { Optional.of(Languages.RO) }
            }
        }
    }

    @LocalServerPort int port
    @Autowired AdminService adminService
    @Autowired TeiDivRepository teiDivRepository
    @Autowired RelocationRepository relocationRepository
    @Autowired RelocationService relocationService
    @Autowired AppUserRepository appUserRepository
    @Autowired ReadingProgressRepository readingProgressRepository
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired PlatformTransactionManager transactionManager

    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    @BeforeEach
    void clean() {
        TestUtils.truncateAllTables(jdbcTemplate)
        Files.deleteIfExists(REPO.resolve(FILE))
    }

    // ------------------------------------------------------------------ TEI

    static String scene(String head, String text) { "<div><head>$head</head><p>$text</p></div>" }

    static String tei(List<String> actIvScenes) {
        """<TEI xmlns="http://www.tei-c.org/ns/1.0">
             <teiHeader><fileDesc><titleStmt><title>Tragedia</title><author>Ion Scriitorul</author></titleStmt></fileDesc></teiHeader>
             <text><body>
               <div><head>Tragedia lui Hamlet, prinț al Danemarcei</head>
                 <div><head>Actul III</head>${scene('O cameră în castel', 'Textul scenei a treia din actul trei.')}</div>
                 <div><head>Actul IV</head>${actIvScenes.join('')}</div>
               </div>
             </body></text></TEI>"""
    }

    static final String S1 = scene('O cameră în castel.', 'Regele și regina vorbesc despre Polonius.')
    static final String S2 = scene('Altă cameră în castel.', 'Hamlet ascunde trupul lui Polonius.')
    static final String S3 = scene('Altă cameră în castel.', 'Regele îl trimite pe Hamlet în Anglia.')
    static final String S4 = scene('O câmpie în Danemarca.', 'Fortinbras trece cu armata spre Polonia.')
    static final String S5 = scene('Finalul actului.', 'O scenă care va dispărea din ediția nouă.')
    // inserted first in the new edition: same heading as S2/S3, new text
    static final String S0 = scene('Altă cameră în castel.', 'O scenă nouă, adăugată la începutul actului.')
    static final String S4_EDITED = scene('O câmpie în Danemarca.', 'Fortinbras trece, cu armata lui, spre Polonia.')

    void write(String content) {
        Files.writeString(REPO.resolve(FILE), content)
    }

    void reimport() {
        adminService.reimportFile(FILE, new NullWriter())
    }

    static final List<String> SENTENCES = [S0, S1, S2, S3, S4, S4_EDITED, S5, 'Încă o scenă nouă',
                                          'Textul scenei a treia din actul trei.']
            .collect { (it =~ /<p>(.*)<\/p>/) ? (it =~ /<p>(.*)<\/p>/)[0][1] : it } as List<String>

    /** each scene's sentence -> its path, read through the scenes' own .txt URLs */
    Map<String, String> pathsByText() {
        final List<String> leaves = new TransactionTemplate(transactionManager).execute {
            teiDivRepository.findAll()
                    .findAll { div -> !div.dbChildren?.any { it instanceof ro.editii.scriptorium.model.TeiDiv } }
                    .collect { '/' + it.completePath }
        } as List<String>
        final Map<String, String> byText = [:]
        for (String path : leaves) {
            final text = get(path + '.txt').body().replaceAll(/\s+/, ' ')
            final sentence = SENTENCES.findAll { text.contains(it) }.max { it.length() }
            if (sentence) byText[sentence] = path
        }
        byText
    }

    Set<String> livePaths() {
        new TransactionTemplate(transactionManager).execute {
            teiDivRepository.findAll().collect { '/' + it.completePath } as Set
        } as Set<String>
    }

    String textAt(String path) {
        pathsByText().find { it.value == path }?.key
    }

    HttpResponse<String> get(String path) {
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    String locationPath(HttpResponse<String> response) {
        final location = URI.create(response.headers().firstValue('Location').orElseThrow())
        location.rawPath + (location.rawQuery ? '?' + location.rawQuery : '')
    }

    // ---------------------------------------------------------------- tests
    //
    // Paths are slugs of headings, numbered in document order. A path a new
    // edition no longer has is relocated (301). A path it still has, but for
    // another division (a same-heading scene inserted before reshuffles the
    // numbering), stays that division's page - a live path is never
    // redirected - while what was saved for the old division follows it.

    static final String S2_RETITLED = scene('Altă încăpere a castelului.', 'Hamlet ascunde trupul lui Polonius.')
    static final String S5_A = scene('Finalul actului patru.', 'O scenă care va dispărea din ediția nouă.')
    static final String S5_B = scene('Încheierea actului patru.', 'O scenă care va dispărea din ediția nouă.')

    @Test
    void aReimportRelocatesEveryVanishedPathToTheSameDivision() {
        write(tei([S1, S2, S3, S4, S5]))
        reimport()
        final Map<String, String> v1 = pathsByText()
        assert v1.size() == 6
        assert relocationRepository.count() == 0, 'a first import relocates nothing'

        // S2 retitled (S3 takes its numbered path), S4 edited, S5 removed
        write(tei([S1, S2_RETITLED, S3, S4_EDITED]))
        reimport()
        final Map<String, String> v2 = pathsByText()
        final Set<String> live = livePaths()

        final String sentence5 = 'O scenă care va dispărea din ediția nouă.'
        v1.each { text, oldPath ->
            if (live.contains(oldPath)) {
                assert relocationService.resolve(oldPath).empty, "live path redirected: $oldPath"
                return
            }
            final target = relocationService.resolve(oldPath).orElseThrow { new AssertionError("lost: $oldPath") }
            if (text == sentence5) {
                assert target.reason == Relocation.Reason.PARENT.name()
                assert target.newPath == oldPath.substring(0, oldPath.lastIndexOf('/')), 'a removed scene goes to its act'
            } else {
                assert target.reason == Relocation.Reason.MOVED.name()
                assert target.newPath == (v2[text] ?: v2['Fortinbras trece, cu armata lui, spre Polonia.']),
                        "$oldPath went to another division: ${target.newPath}"
            }
        }
        // S3 took S2's numbered path, and its own old one is relocated there
        assert !live.contains(v1['Regele îl trimite pe Hamlet în Anglia.'])
        assert relocationService.resolve(v1['Regele îl trimite pe Hamlet în Anglia.']).get().newPath ==
                v2['Regele îl trimite pe Hamlet în Anglia.']
        assert relocationRepository.findAll().every { !live.contains(it.oldPath) }
        assert v2.values().every { relocationService.resolve(it).empty }
    }

    @Test
    void oldUrlsAnswer301ToTheNewOnesAndLivePathsAreNeverRedirected() {
        write(tei([S1, S2, S3, S4, S5]))
        reimport()
        final String oldS5 = pathsByText()['O scenă care va dispărea din ediția nouă.']
        write(tei([S1, S2, S3, S4, S5_A]))
        reimport()
        final String newS5 = pathsByText()['O scenă care va dispărea din ediția nouă.']
        assert oldS5 != newS5

        // the reading page
        def response = get(oldS5)
        assert response.statusCode() == 301
        assert locationPath(response) == newS5
        assert get(newS5).statusCode() == 200

        // the reader's content requests keep their extension and query
        response = get("${oldS5}.html?depth=0&fragments=1")
        assert response.statusCode() == 301
        assert locationPath(response) == "${newS5}.html?depth=0&fragments=1"
        response = get("${oldS5}.txt")
        assert response.statusCode() == 301
        assert locationPath(response) == "${newS5}.txt"

        // the REST lookup by path
        response = get("/api/divs?path=${oldS5.substring(1)}")
        assert response.statusCode() == 301
        assert locationPath(response) == "/api/divs?path=${newS5.substring(1)}"
        assert get("/api/divs?path=${newS5.substring(1)}").statusCode() == 200

        // where it lives now, for clients navigating by path
        response = get("/api/relocations?path=${oldS5}")
        assert response.statusCode() == 200
        final json = new JsonSlurper().parseText(response.body())
        assert json.oldPath == oldS5 && json.newPath == newS5 && json.reason == 'MOVED'
        assert get("/api/relocations?path=${newS5}").statusCode() == 404

        // a live path wins over a (hand-made, stale) relocation of it
        relocationRepository.save(new Relocation(newS5, '/elsewhere/entirely', 'MANUAL', null))
        assert get(newS5).statusCode() == 200
        // and an unknown path stays a 404
        assert get(oldS5 + '_nope').statusCode() == 404
    }

    @Test
    void revertingReclaimsPathsAndChainsCollapse() {
        final String sentence = 'O scenă care va dispărea din ediția nouă.'
        write(tei([S1, S2, S3, S4, S5]))
        reimport()
        final String p1 = pathsByText()[sentence]
        write(tei([S1, S2, S3, S4, S5_A]))
        reimport()
        final String p2 = pathsByText()[sentence]
        write(tei([S1, S2, S3, S4, S5_B]))
        reimport()
        final String p3 = pathsByText()[sentence]
        assert [p1, p2, p3].unique().size() == 3

        // no hops left: both older paths point straight at the newest
        assert relocationRepository.findById(p1).get().newPath == p3
        assert relocationRepository.findById(p2).get().newPath == p3
        assert locationPath(get(p1)) == p3

        // back to the first edition: its path is live again, never redirected,
        // and the later editions' paths lead back to it
        write(tei([S1, S2, S3, S4, S5]))
        reimport()
        final Set<String> live = livePaths()
        assert live.contains(p1)
        assert relocationRepository.findById(p1).empty
        assert get(p1).statusCode() == 200
        assert relocationService.resolve(p2).get().newPath == p1
        assert relocationService.resolve(p3).get().newPath == p1
        assert relocationRepository.findAll().every { !live.contains(it.oldPath) }
    }

    @Test
    void aReshuffleKeepsEveryPathLiveAndSavedReadingFollowsTheDivision() {
        write(tei([S1, S2, S3, S4, S5]))
        reimport()
        final Map<String, String> v1 = pathsByText()
        final String oldS3 = v1['Regele îl trimite pe Hamlet în Anglia.']
        final String opus = oldS3.split('/')[1..2].join('/')
        final user = appUserRepository.save(AppUser.builder().username('reader-relocation').build())
        readingProgressRepository.save(ReadingProgress.builder().user(user).opusPath(opus).divPath(oldS3.substring(1)).build())

        // a new same-heading scene first: S2 and S3 shift down one numbered path
        write(tei([S0, S1, S2, S3, S4, S5]))
        reimport()
        final Map<String, String> v2 = pathsByText()
        final String newS3 = v2['Regele îl trimite pe Hamlet în Anglia.']
        assert oldS3 != newS3
        // every old path is still a page (of whichever scene holds it now)
        v1.values().each { assert get(it).statusCode() == 200 }
        // the reader's place moved with the scene it was reading
        assert jdbcTemplate.queryForObject('SELECT div_path FROM reading_progress', String) == newS3.substring(1)
    }

    @Test
    void aDestroyAllReimportRelocatesToo() {
        write(tei([S1, S2, S3, S4, S5]))
        adminService.reimportAllTeis(new NullWriter())
        final String oldS5 = pathsByText()['O scenă care va dispărea din ediția nouă.']
        write(tei([S1, S2, S3, S4, S5_A]))
        adminService.destroyAllExistingAndReimportAllTeis(new NullWriter(), true)
        final String newS5 = pathsByText()['O scenă care va dispărea din ediția nouă.']
        assert oldS5 != newS5
        assert relocationService.resolve(oldS5).get().newPath == newS5
        assert get(oldS5).statusCode() == 301
    }

    @Test
    void headingsThatGainedALabelAreStillRecognised() {
        write(tei([S1, S2, S3, S4, S5]))
        reimport()
        final Map<String, String> v1 = pathsByText()
        // the new edition numbers its scenes and edits every text a little
        final List<String> labelled = [S1, S2, S3, S4, S5].withIndex().collect { String scene, int i ->
            scene.replace('<head>', "<head>Scena ${['I', 'II', 'III', 'IV', 'V'][i]}. ")
                 .replace('</p>', ' (ediție revăzută)</p>')
        }
        write(tei(labelled))
        reimport()
        final Set<String> live = livePaths()
        v1.each { text, oldPath ->
            if (text.startsWith('Textul scenei')) return // Act III, untouched
            assert !live.contains(oldPath), "the labels must change the paths: $oldPath"
            final target = relocationService.resolve(oldPath).orElseThrow { new AssertionError("lost: $oldPath") }
            assert target.reason == Relocation.Reason.MOVED.name()
            assert get(target.newPath + '.txt').body().contains(text), "$oldPath went to the wrong scene: ${target.newPath}"
        }
    }

    @Test
    void aTeiRewrittenBeyondRecognitionImportsWithoutErrors() {
        write(tei([S1, S2, S3, S4, S5]))
        reimport()
        final Map<String, String> v1 = pathsByText()
        // everything changed: work title, acts, scenes, texts
        write('''<TEI xmlns="http://www.tei-c.org/ns/1.0">
             <teiHeader><fileDesc><titleStmt><title>Altceva</title><author>Ion Scriitorul</author></titleStmt></fileDesc></teiHeader>
             <text><body><div><head>O carte cu totul alta</head>
                 <div><head>Capitolul unu</head><p>Nimic din ce a fost.</p></div>
                 <div><head>Capitolul doi</head><p>Nici aici.</p></div>
             </div></body></text></TEI>''')
        reimport()
        // imported fine, the new work is there
        assert livePaths().any { it.endsWith('/capitolul_unu') }
        // what cannot be recognised is not invented: no relocation points into the new work
        assert relocationRepository.findAll().every { r -> v1.values().contains(r.oldPath) }
        v1.values().each { assert get(it).statusCode() in [301, 404] }
    }

    @Test
    void aFailureRecordingRelocationsNeverFailsTheImport() {
        write(tei([S1, S2, S3, S4, S5]))
        reimport()
        write(tei([S0, S1, S2, S3, S4, S5]))
        jdbcTemplate.execute('ALTER TABLE relocation RENAME TO relocation_away')
        try {
            reimport() // the relocation writes fail; the import must not
        } finally {
            jdbcTemplate.execute('ALTER TABLE relocation_away RENAME TO relocation')
        }
        final Map<String, String> v2 = pathsByText()
        assert v2.containsKey('O scenă nouă, adăugată la începutul actului.'), 'the new edition was imported'
        v2.values().each { assert get(it).statusCode() == 200 }
    }
}
