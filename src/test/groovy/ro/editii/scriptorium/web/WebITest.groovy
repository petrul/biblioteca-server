package ro.editii.scriptorium.web

import editii.commons.xml.XpathTool
import org.apache.commons.io.FileUtils
import org.apache.commons.io.output.NullWriter
import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.lang3.time.StopWatch
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.jdbc.JdbcTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import ro.editii.scriptorium.GTestUtil
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.cache.DiskCache
import ro.editii.scriptorium.client.TextbaseClient
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.RelocationRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.Relocation
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.scheduled.NoWriter
import ro.editii.scriptorium.service.AdminService
import ro.editii.scriptorium.service.DivService
import ro.editii.scriptorium.tei.TeiRepo
import ro.editii.scriptorium.tei.TeifileParser
import ro.editii.scriptorium.toc.Toc

import javax.imageio.ImageIO
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

import static editii.commons.xml.TeiDocument.XPATH_BODY
import static ro.editii.scriptorium.GTestUtil.p
import static ro.editii.scriptorium.TestUtils.TEI_ELEM

@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:myDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        // Isolated from the real dev/int/prod Lucene index at
        // ~/.textbase/cache/lucene-index (lucene.index.dir's own default
        // otherwise) - without this, these tests shared a single-writer
        // Lucene lock with whatever's actually running on this machine,
        // and could block on (or corrupt) a real, unrelated index.
        "lucene.index.dir=\${java.io.tmpdir}/webitest-lucene-index",
        // The auto-build-on-startup thread would otherwise race this
        // class's own explicit imports/incremental reindexing.
        "lucene.autoindex.enabled=false",
        // Inherits application-ci.properties's enrichment.enabled=false -
        // this class's many reimports (relocationWorks,
        // destroyAllExistingAndReimportAllTeis, @AfterEach's own
        // full-corpus recovery reimport, etc.) would otherwise each fire
        // real Ollama/SearXNG enrichment calls as an untested side effect.
        // AiEnrichmentITest is the one test in the whole suite that
        // exercises that real integration, in its own isolated context.
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TestConfig.class])
@EnableAutoConfiguration(exclude= [KafkaAutoConfiguration.class])
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebITest {

    @LocalServerPort int port
    @Autowired TextbaseClient tbc;
    @Autowired DivController divController
    @Autowired @Qualifier(TestConfig.REST_TEMPLATE_NO_REDIRECT) RestTemplate restTemplate
    @Autowired RestTemplate restTemplateNoRedirect
    @Autowired TeiRepo teiRepo
    @Autowired TeiDivRepository teiDivRepository
    @Autowired AuthorRepository authorRepository
    @Autowired AdminService adminService
    @Autowired RelocationRepository relocationRepository
    @Autowired DivService divService
    @Autowired PlatformTransactionManager platformTransactionManager
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired TeifileParser parser

    @MockitoBean TextbaseEventsPublisher textbaseEventsPublisher;

    static final String DUMBRAVA_ROSIE = '/alecsandri/legende/dumbrava_rosie'
    final urls = [
            "/creanga/povesti"          : "Iubite cetitoriu,",
            '/alecsandri/legende/dumbrava_rosie'     : "dedicat amicului meu C. Negri",
    ]


    void truncateAllTables() {
        TestUtils.truncateAllTables(this.jdbcTemplate)
    }

    protected reimportTeis() {
        adminService.reimportAllTeis(new NullWriter())
    }

    File cacheDir
    int importedAuthorCount

    @BeforeAll
    void beforeAll() {
        final String tmpdir = TestUtils.tmpDir
        this.cacheDir = new File(tmpdir, "WebItest-" + ro.editii.scriptorium.TestUtils.randomString())
        Files.createDirectories(this.cacheDir.toPath())
        p "created basedir ${this.cacheDir}"

        this.reimportEverything()
        this.importedAuthorCount = countTableRows("author")
    }

    void reimportEverything() {
        this.truncateAllTables()
        reimportTeis()

        assert countTableRows("author") > 0
        assert countTableRows("tei_file_authors") > 0
        assert countTableRows(TEI_ELEM) > 0
    }

    def countTableRows(String tableName) {
        TestUtils.countTableRows(this.jdbcTemplate, tableName)
    }

    @AfterEach
    void afterEach() {
        // Most tests are read-only and can share the imported fixture. Restore it
        // only after a test (currently relocationWorks) replaced the dataset.
        if (countTableRows("author") != this.importedAuthorCount) {
            this.reimportEverything()
        }
    }

    @AfterAll
    void afterAll() {
        FileUtils.deleteDirectory(this.cacheDir)
        p "deleted basedir ${this.cacheDir}"
    }

    @Test
    void davExportIsReachableThroughSecurityAndServesCorpusContent() {
        final client = java.net.http.HttpClient.newHttpClient()
        final txtMount = '/dav/_export/txt/1.1/ro/alecsandri/'

        final options = java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:${port}${txtMount}"))
                .method('OPTIONS', java.net.http.HttpRequest.BodyPublishers.noBody())
                .build()
        final optionsResponse = client.send(options, java.net.http.HttpResponse.BodyHandlers.discarding())
        assert optionsResponse.statusCode() == 204
        assert optionsResponse.headers().firstValue('DAV').orElse(null) == '1'
        assert optionsResponse.headers().firstValue('Allow').orElse('').contains('PROPFIND')

        final rootPropfind = java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:${port}${txtMount}"))
                .header('Depth', '1')
                .method('PROPFIND', java.net.http.HttpRequest.BodyPublishers.noBody())
                .build()
        final rootListing = client.send(rootPropfind, java.net.http.HttpResponse.BodyHandlers.ofString())

        assert rootListing.statusCode() == 207
        assert rootListing.headers().firstValue('DAV').orElse(null) == '1'
        assert rootListing.body().contains("${txtMount}ro/")
        assert !rootListing.body().contains("${txtMount}fr/")

        final languagePropfind = java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:${port}${txtMount}ro/"))
                .header('Depth', '1')
                .method('PROPFIND', java.net.http.HttpRequest.BodyPublishers.noBody())
                .build()
        final languageListing = client.send(languagePropfind, java.net.http.HttpResponse.BodyHandlers.ofString())
        assert languageListing.statusCode() == 207
        assert languageListing.body().contains("${txtMount}ro/alecsandri/")
        assert !languageListing.body().contains("${txtMount}ro/creanga/")
        assert !languageListing.body().contains("${txtMount}ro/cantemir/")

        final authorPropfind = java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:${port}${txtMount}ro/alecsandri/"))
                .header('Depth', '1')
                .method('PROPFIND', java.net.http.HttpRequest.BodyPublishers.noBody())
                .build()
        final authorListing = client.send(authorPropfind, java.net.http.HttpResponse.BodyHandlers.ofString())
        assert authorListing.statusCode() == 207
        assert authorListing.body().contains("${txtMount}ro/alecsandri/legende/")
        assert !authorListing.body().contains("${txtMount}ro/alecsandri/legende.txt")

        final workPropfind = java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:${port}${txtMount}ro/alecsandri/legende/"))
                .header('Depth', '1')
                .method('PROPFIND', java.net.http.HttpRequest.BodyPublishers.noBody())
                .build()
        final workListing = client.send(workPropfind, java.net.http.HttpResponse.BodyHandlers.ofString())
        assert workListing.statusCode() == 207
        assert workListing.body().contains("${txtMount}ro/alecsandri/legende/legenda_ciocarliei.txt")

        final fragmentGet = java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:${port}${txtMount}ro/alecsandri/legende/legenda_ciocarliei.txt"))
                .GET().build()
        final fragmentContent = client.send(fragmentGet, java.net.http.HttpResponse.BodyHandlers.ofString())
        assert fragmentContent.statusCode() == 200
        assert fragmentContent.body().contains('Zbori în soare')
        assert !fragmentContent.body().contains('\nLegende\n')

        final expectedContentTypes = [
                txt: 'text/plain',
                json: 'application/json',
                xml: 'application/xml',
                xhtml: 'application/xhtml+xml',
        ]
        expectedContentTypes.each { format, contentType ->
            final formatMount = "/dav/_export/${format}/1/ro/alecsandri/"
            final get = java.net.http.HttpRequest.newBuilder(
                    URI.create("http://localhost:${port}${formatMount}ro/alecsandri/legende.${format}"))
                    .GET().build()
            final content = client.send(get, java.net.http.HttpResponse.BodyHandlers.ofString())
            assert content.statusCode() == 200
            assert content.headers().firstValue('Content-Type').orElse('').startsWith(contentType)
            assert content.headers().firstValue('ETag').isPresent()
            assert content.headers().firstValue('Last-Modified').isPresent()
            assert content.body().contains('Legende')

            if (format == 'json')
                assert new groovy.json.JsonSlurper().parseText(content.body()) instanceof Map
            if (format == 'xml' || format == 'xhtml')
                assert new XmlSlurper(false, true).parseText(content.body()) != null
        }

        final head = java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:${port}${txtMount}ro/alecsandri/legende/legenda_ciocarliei.txt"))
                .method('HEAD', java.net.http.HttpRequest.BodyPublishers.noBody())
                .build()
        final headResponse = client.send(head, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        assert headResponse.statusCode() == 200
        assert headResponse.headers().firstValueAsLong('Content-Length').orElse(0) > 0
        assert headResponse.body().length == 0

        final infinite = java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:${port}${txtMount}"))
                .header('Depth', 'infinity')
                .method('PROPFIND', java.net.http.HttpRequest.BodyPublishers.noBody())
                .build()
        final infiniteResponse = client.send(infinite, java.net.http.HttpResponse.BodyHandlers.ofString())
        assert infiniteResponse.statusCode() == 403
        assert infiniteResponse.body().contains('propfind-finite-depth')

        ['PUT', 'DELETE', 'MKCOL', 'COPY', 'MOVE', 'PROPPATCH', 'LOCK', 'UNLOCK'].each { method ->
            final write = java.net.http.HttpRequest.newBuilder(
                    URI.create("http://localhost:${port}${txtMount}"))
                    .method(method, java.net.http.HttpRequest.BodyPublishers.ofString('forbidden'))
                    .build()
            final writeResponse = client.send(write, java.net.http.HttpResponse.BodyHandlers.discarding())
            assert writeResponse.statusCode() == 405
            assert writeResponse.headers().firstValue('Allow').orElse('') == 'OPTIONS, PROPFIND, GET, HEAD'
        }
    }

    @Test
    void rootRedirectsModernBrowsersToTheReaderAppButServesEveryoneElseTheIndexPage() {
        final client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build()

        final modernUserAgents = [
                'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
                'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15',
                'Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0',
                'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0.6478.54 Mobile/15E148 Safari/604.1',
        ]
        modernUserAgents.each { ua ->
            final request = java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:${port}/"))
                    .header('User-Agent', ua)
                    .GET().build()
            final response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
            assert response.statusCode() == 302 : "expected a redirect for UA: ${ua}"
            // sendRedirect() turns the "redirect:/app" view's relative
            // target into an absolute URL per the servlet spec - assert
            // on the path only, not the scheme/host.
            assert response.headers().firstValue('Location').orElse('') == "http://localhost:${port}/app" : "wrong redirect target for UA: ${ua}"
        }

        final nonBrowserOrOldUserAgents = [
                'curl/8.4.0',
                'Wget/1.21.4',
                'python-requests/2.31.0',
                'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)',
                'Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1)',
                'Lynx/2.9.2 libwww-FM/2.14',
                '',
        ]
        nonBrowserOrOldUserAgents.each { ua ->
            final builder = java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:${port}/")).GET()
            if (ua) builder.header('User-Agent', ua)
            final response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            assert response.statusCode() == 200 : "expected the index page for UA: '${ua}'"
            assert response.body().contains('<html') : "expected HTML index body for UA: '${ua}'"
        }

        // No User-Agent header at all (some HEAD-ish/legacy clients) is
        // just as "not a modern browser" as an empty one.
        final noUaRequest = java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:${port}/"))
                .GET().build()
        final noUaResponse = client.send(noUaRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
        assert noUaResponse.statusCode() == 200
        assert noUaResponse.body().contains('<html')
    }

    @Test
    void noredirectQueryParameterServesTheMvcIndexToModernBrowsers() {
        final client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build()
        final request = java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:${port}/?noredirect"))
                .header('User-Agent',
                        'Mozilla/5.0 Chrome/128.0.0.0 Safari/537.36')
                .GET().build()

        final response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())

        assert response.statusCode() == 200
        assert response.body().contains('<html')
    }

    List<Map> luceneSearchFor(String q) {
        final client = java.net.http.HttpClient.newHttpClient()
        final request = java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:${port}/api/search/lucene?q=" + java.net.URLEncoder.encode(q, 'UTF-8')))
                .GET().build()
        final response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        assert response.statusCode() == 200
        (List<Map>) new groovy.json.JsonSlurper().parseText(response.body())
    }

    @Test
    void theLuceneIndexIsIncrementallyBuiltByTheOrdinaryImportPathWithNoManualReindexNeeded() {
        // beforeAll() already imported the fixture corpus via
        // reimportAllTeis() - nothing in this test class ever calls
        // POST /api/admin/lucene/reindex (AdminService.reindexLucene),
        // yet a real, known term from that corpus is already searchable,
        // because AdminService.reindexLuceneForImportedFile now runs as
        // a side effect of every successful TEI import.
        final hits = luceneSearchFor('cetitoriu')
        assert hits.size() > 0
        assert hits.any { it.url.toString().startsWith('creanga/povesti') }
    }

    @Test
    void reimportingASingleTeiFileReindexesOnlyItsOwnOperaWithoutDuplicatingEntries() {
        final before = luceneSearchFor('cetitoriu').findAll { it.url.toString().startsWith('creanga/povesti') }
        assert before.size() > 0

        this.adminService.reimportFile('/ro/Creanga-Amintiri_din_copilarie.xml', new NullWriter())

        final after = luceneSearchFor('cetitoriu').findAll { it.url.toString().startsWith('creanga/povesti') }
        // Same URLs, same count - not doubled by the reimport, and not
        // wiped either (LuceneIndexService.reindexOpus's delete-then-add
        // targets exactly this opus's own documents).
        assert after.size() == before.size()
        assert after.collect { it.url }.sort() == before.collect { it.url }.sort()

        // A DIFFERENT opus's entries must be completely untouched by that
        // single-file reimport - the whole point of doing this per-opus
        // rather than a full rebuild.
        final otherOpusHits = luceneSearchFor('Negri').findAll { it.url.toString().startsWith('alecsandri/legende/dumbrava_rosie') }
        assert otherOpusHits.size() > 0
    }

    @Test
    void authorNativeLanguageIsBackfilledFromTheImportedOpusLanguage() {
        // Purely synchronous DB state, set right during postImportHooks -
        // no need to wait for anything async here (unlike the bio/summary
        // enrichment itself, covered separately below).
        final creanga = this.authorRepository.findByStrId('creanga').first()
        assert creanga.nativeLanguage == ro.editii.scriptorium.model.Languages.RO
    }

    // aiEnrichmentEventuallyFillsInARealAuthorBioAndOpusSummary() moved to
    // AiEnrichmentITest - see that class's doc comment for why.

    @Test
    void exportedOpenApiYamlIsValidAndInternallyConsistent() {
        final request = java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:${port}/api/docs.yaml"))
                .header('Accept', 'application/vnd.oai.openapi')
                .GET().build()
        final response = java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())

        assert response.statusCode() == 200
        assert response.body().contains('openapi:')

        final yamlMapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
        yamlMapper.findAndRegisterModules()
        final document = yamlMapper.readTree(response.body())

        assert document.path('openapi').asText().startsWith('3.')
        assert document.path('info').isObject()
        assert document.path('paths').isObject()
        assert document.path('paths').size() > 0
        assert document.path('paths').has('/api/divs')
        assert document.path('paths').has('/api/authors/{strId}')
        assert document.path('components').path('schemas').size() > 0
        assert !document.path('paths').has('/dav')

        final httpMethods = ['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace'] as Set
        document.path('paths').fields().each { pathEntry ->
            pathEntry.value.fields().each { operation ->
                if (httpMethods.contains(operation.key)) {
                    assert operation.value.path('responses').isObject():
                            "${operation.key.toUpperCase()} ${pathEntry.key} has no responses object"
                    assert operation.value.path('responses').size() > 0:
                            "${operation.key.toUpperCase()} ${pathEntry.key} has no documented response"
                }
            }
        }

        document.findValues('$ref').each { referenceNode ->
            final reference = referenceNode.asText()
            if (reference.startsWith('#/'))
                assert !document.at(reference.substring(1)).isMissingNode(): "unresolved OpenAPI reference ${reference}"
        }
    }

    @Test
    void getCreangaPovesti() {
        assert divController != null

        // default served is html -> TODO put this back
//        assert this.tbc.getTextHtml('/alecsandri/legende/dumbrava_rosie') == this.tbc.getDefault('/alecsandri/legende/dumbrava_rosie')

        // twice because we need to test also the cache behaviour
        2.times { time ->
            printHeader(time)

            urls.each { k, v ->
                def expected_text = v
                assert this.tbc.getTextHtml(k).contains(expected_text)
            }
        }

        // test different formats

        _1: {
            final path = DUMBRAVA_ROSIE
            // test different formats are available
            final htmlDecorated = this.tbc.getTextHtml(path)
            final xml = this.tbc.getTextXml(path)
            final txt = this.tbc.getTextPlain(path)

            assert ! htmlDecorated.empty
            assert ! xml.empty
            assert ! txt.empty

            assert htmlDecorated != xml
            assert xml != txt

            assert htmlDecorated.length() != xml.length()
            assert xml.length() != txt.length()
        }

        _2: {
            // txt1 is an ancestor of txt2, which is an ancestor of the leaf txt3 -
            // a non-leaf fragment's .txt must include every descendant sub-chapter's
            // text (not just what's directly under it, between the sub-chapters),
            // so anything found in a descendant must also be found in all its
            // ancestors. Only the reverse never holds: a fragment never contains
            // text that lives strictly above it.
            final txt1 = this.tbc.getTextPlain('/alecsandri/legende/')
            final txt2 = this.tbc.getTextPlain('/alecsandri/legende/legenda_ciocarliei')
            final txt3 = this.tbc.getTextPlain('/alecsandri/legende/legenda_ciocarliei/iii')

            assert ! txt1.empty
            assert ! txt2.empty
            assert ! txt3.empty

            // 'Legende' is txt1's own head - neither descendant carries an
            // ancestor's heading.
            final legendeTxt = 'Legende'
            assert   txt1.contains(legendeTxt)
            assert ! txt2.contains(legendeTxt)
            assert ! txt3.contains(legendeTxt)

            // the epigraph directly under legenda_ciocarliei (not under any of its
            // numbered sections) - present in it and in its ancestor, absent from
            // the leaf section which is a sibling of where the epigraph actually is
            final zbori_in_soare = 'Zbori în soare'
            assert   txt1.contains(zbori_in_soare)
            assert   txt2.contains(zbori_in_soare)
            assert ! txt3.contains(zbori_in_soare)

            // section III's own text - present in the leaf itself and in both its
            // ancestors now that a non-leaf fragment recurses into its sub-chapters
            final in_revărsatul_zilei = 'În revărsatul zilei, când nasc a vieții șoapte'
            assert   txt1.contains(in_revărsatul_zilei)
            assert   txt2.contains(in_revărsatul_zilei)
            assert   txt3.contains(in_revărsatul_zilei)
            assert   txt3.contains("III\n\nÎn revărsatul zilei, când nasc a vieții șoapte\nȘi lin se dezvelește seninul cer din noapte,")

            // depth=1 on txt1 (legende) keeps one level below it in full - legenda_ciocarliei's
            // own epigraph - but prunes anything nested a level deeper still - legenda_ciocarliei's
            // OWN sections (i/ii/iii), two levels below legende. Distinguishes depth=1 from both
            // depth=0 (would also drop the epigraph) and the unlimited default (would also keep III).
            final txt1Depth1 = this.tbc.getTextPlain('/alecsandri/legende/?depth=1')
            assert   txt1Depth1.contains(legendeTxt)
            assert   txt1Depth1.contains(zbori_in_soare)
            assert ! txt1Depth1.contains(in_revărsatul_zilei)
        }

        _3: {

            final txt1 = this.tbc.getTextPlain('/cantemir/descrierea_moldovei')
            final txt2 = this.tbc.getTextPlain('/cantemir/descrierea_moldovei/partea_eclesiastica_si_literara/despre_literile_moldovenilor')

            assert !txt1.empty
            assert !txt2.empty

            assert txt1.contains('[IMAGE]')
            assert txt1.contains('Hartă apărută în ediţia germană a lui Büsching')

            assert txt2.contains('Despre literile Moldovenilor')
            assert txt2.contains('Mai nainte de soborul de la Florenția avea Moldovenii litere Latinești')
            assert txt2.contains('CAP. V \nDespre literile Moldovenilor')
        }
    }

    @Test
    void getOpusDivWorks() {

        urls.keySet().each {url ->
            assert this.tbc.getTextXml(url).length() > 0
            assert this.tbc.getTextPlain("${url}.txt").length() > 0
            assert this.tbc.getDefault("${url}.xml").length() > 0
            assert this.tbc.getDefault("${url}.json").length() > 0
            assert this.tbc.getDefault("${url}.html").length() > 0

            final decoratedHtml = this.tbc.getDefault(url)
            assert decoratedHtml.length() > 0
            assert decoratedHtml.contains("<h")
        }


    }

    /**
     * a non-leaf fragment (like /creanga/povesti) defaults to its FULL text,
     * subchapters included, in every format - EXCEPT the decorated reader
     * page (no extension - the actual Spring MVC "teidiv" UI, with its own
     * breadcrumb/prev/next/children nav below), which stays chapter-by-
     * chapter as it always was: it defaults to depth=0 instead, and only
     * goes deeper if a caller explicitly asks via ?depth=. Otherwise the
     * reader would inline an entire work's text onto a single page.
     */
    @Test
    void nonLeafFragmentDefaultsToFullTextDepthParamLimitsIt() {
        assert countTableRows("author") > 0
        assert countTableRows("tei_file_authors") > 0
        assert countTableRows(TEI_ELEM) > 0
        assert !this.jdbcTemplate.queryForList("select * from author").isEmpty()

        final File f = teiRepo.getFile("/ro/Creanga-Amintiri_din_copilarie.xml")
        assert f.exists() && f.canRead()

        final xt = new XpathTool(new FileInputStream(f), f.getAbsolutePath())
        final xpath_for_povesti =  XPATH_BODY + "/tei:div[2]"
        final xpath_for_soacra_cu_3_nurori  =  XPATH_BODY + "/tei:div[2]/tei:div[1]"
        assert xpath_for_povesti != xpath_for_soacra_cu_3_nurori
        assert xpath_for_soacra_cu_3_nurori.startsWith(xpath_for_povesti)
        assert xpath_for_soacra_cu_3_nurori.length() > xpath_for_povesti.length()

        assert xt.applyXpathForNodeSet(xpath_for_povesti).length == 1
        assert xt.applyXpathForNodeSet(xpath_for_soacra_cu_3_nurori).length == 1
        assert xt.applyXpathForNodeSet(xpath_for_soacra_cu_3_nurori + "/ttt").length == 0

        // the decorated reader page (no extension) - unchanged, chapter-by-chapter behavior
        final String readerDefault = this.tbc.getTextHtml("/creanga/povesti")

        assert readerDefault.contains("Iubite cetitoriu") // still this div's own content
        assert readerDefault.contains("Soacra cu trei nurori") // the front-matter toc listing, not the sub-story div itself
        assert !readerDefault.contains("Era odată o babă") // no sub-story body inlined by default
        assert isDecoratedHtml(readerDefault)

        // but an explicit ?depth= still expands the reader page too, if asked for
        final String readerExpanded = this.tbc.getTextHtml("/creanga/povesti?depth=1")
        assert readerExpanded.contains("Era odată o babă")
        assert isDecoratedHtml(readerExpanded)

        // the undecorated .html (no site chrome - a raw export format like .txt/.xml/.json)
        // defaults to full text instead, same as the other formats
        final String undecoratedFull = this.tbc.getDefault("/creanga/povesti.html")
        assert undecoratedFull.contains("Iubite cetitoriu")
        assert undecoratedFull.contains("Era odată o babă") // full text by default, unlike the reader page

        final String undecoratedShallow = this.tbc.getDefault("/creanga/povesti.html?depth=0")
        assert undecoratedShallow.contains("Iubite cetitoriu")
        assert !undecoratedShallow.contains("Era odată o babă")

        // the actual sub-story on its own always contains its own text, regardless of depth
        final String text2 = this.tbc.getTextHtml("/creanga/povesti/soacra_cu_trei_nurori")

        assert !text2.empty
        assert text2.contains("Era odată o babă, care avea trei feciori înalți ca niște brazi și tari de virtute, dar slabi de minte.")
        assert isDecoratedHtml(text2)

    }

    @Test
    void alecsandri_suvenire_maiorului_iancu_bran() {

        2.times { time ->
            final text = this.tbc.getTextHtml("/alecsandri/suvenire/maiorului_iancu_bran")
            assert text != null
            assert !text.isEmpty()
            assert text.contains("<h3>Maiorului Iancu Bran")
            assert text.contains("Mergi să-ți iei dreapta răsplată de la dreptul ziditor")
            assert text.contains("Tu ce lași în urmă jale, vrednicule muritor!")
            assert isDecoratedHtml(text)
        }

    }

    @Test
    void cantemirGetBinaryJpeg() {
        // you can get the _binary at any level, as long as you have the id
        final urls = [
                url("/cantemir/descrierea_moldovei/_binary/d3e1954"),
                url("/cantemir/descrierea_moldovei/partea_eclesiastica_si_literara/_binary/d3e1954"),
                url("/cantemir/descrierea_moldovei/partea_eclesiastica_si_literara/despre_literile_moldovenilor/_binary/d3e1954")
        ]
        for (String url : urls) {
            final resp = this.restTemplate.getForEntity(url, byte[].class)

            final contentTypeHeader = resp.headers.get('Content-Type')
            assert contentTypeHeader == ['image/jpeg']
            final bytes = resp.body
            assert bytes != null
            assert bytes.length > 0

            final image = ImageIO.read(new ByteArrayInputStream(bytes))

            assert image != null
            assert image.width == 1166
            assert image.height == 1220

        }
    }

    @Test
    void testCantemirDescrierea_page_actually_contains_correct_image_link() {

        2.times { t ->
            final pageUrl = "/cantemir/descrierea_moldovei"
            final content = this.tbc.getTextHtml(pageUrl)
            assert content.contains('src="/cantemir/descrierea_moldovei/_binary/d3e1954"')

            // assert page's twitter and facebook card images point to that image
            final lines = content.split("\n").findAll(it -> it.contains('meta property="og:image"'))
            assert lines != null
            assert lines.size() == 1

            final imageUrl = "http://localhost:${port}/cantemir/descrierea_moldovei/_binary/d3e1954"
            assert lines.first().contains(imageUrl)
            assert content.split("\n").findAll { fit -> fit.contains('meta name="twitter:image"') }.first().contains(imageUrl)
            assert isDecoratedHtml(content)
        }

    }

    def isDecoratedHtml(String str) {
        return str.contains('<ul class="breadcrumbs">')
    }

    @Test
    void testRandomTool() {

        final url = url('/util/random')
        final resp = this.restTemplateNoRedirect
                .getForEntity(url, String.class)
        assert resp.statusCode == HttpStatus.FOUND

        final locations = resp.headers.get("Location")
        assert locations != null
        assert locations.size() == 1

        final location = locations[0]
        assert location.length() > 1

        def urlStart = "http://localhost:${port}/"
        assert location.startsWith(urlStart)
        assert location.substring(urlStart.length()).length() > 5
    }

    @Test
    void relocationWorks() {
        2.times { time ->
            p "=" * 80
            p "= PASS ${time}"
            p "=" * 80

            final authorId = 'alecsandri'
            final opId = 'poezii'

            final tt = new TransactionTemplate(this.platformTransactionManager)
            def executor = Executors.newSingleThreadExecutor()

            this.truncateAllTables()
            p this.authorRepository.findAll().strId
            assert this.authorRepository.getByStrId(authorId).empty
            assert JdbcTestUtils.countRowsInTable(this.jdbcTemplate, 'tei_elem') == 0
            assert JdbcTestUtils.countRowsInTable(this.jdbcTemplate, 'author') == 0

            final tei = GTestUtil.teiOf("Văsălie Alecsandri", """
                <div>
                    <head>Poezii</head>
                    <p>content</p>
                </div>
            """)
            this.parser.parse(tei, Languages.ES)

            // make sure data commited on test main thread is visible from another thread
            final mainThreadName = Thread.currentThread().name
            assert this.authorRepository.getByStrId(authorId).present
            final fut = executor.submit(() -> {
                assert mainThreadName != Thread.currentThread().name
                assert this.authorRepository.getByStrId(authorId).present
            })
            fut.get(1, TimeUnit.MINUTES)

            assert this.divService.getOpera(authorId).collect { it.urlFragment }.contains(opId)
            assert this.teiDivRepository
                    .findOperaForAuthorStrId(authorId)
                    .collect { it.urlFragment }
                    .contains(opId)

            final String nonExistentPath = "/$authorId/$opId/" + RandomStringUtils.randomAlphanumeric(200)
            final String relocationPath = RandomStringUtils.randomAlphanumeric(200);

            // this is the author page, it should exist

            final authorUrl = url("$authorId")
            assert this.restTemplateNoRedirect.getForEntity(authorUrl, String.class).body.containsIgnoreCase(authorId)

            // assert not teidiv points to that randomly generated 200-char string
            assert JdbcTestUtils.countRowsInTable(jdbcTemplate, 'relocation') == 0
            tt.execute(status -> {
                assert this.teiDivRepository.findAll().stream()
                        .noneMatch(it -> it.getCompletePath() == nonExistentPath)
            })

            // getting it by url should fail

            final nonExistentUrl = url(nonExistentPath)

            try {
                this.restTemplateNoRedirect.getForEntity(nonExistentUrl, String.class)
                Assertions.fail("call should fail")
            } catch (HttpClientErrorException e) {
                assert e.statusCode == HttpStatus.NOT_FOUND
                assert e.responseHeaders.get(HttpHeaders.LOCATION) == null
            }

            // insert relocation into db
            assert this.relocationRepository.findById(nonExistentPath).empty

            tt.execute((status) -> {
                this.relocationRepository.save(Relocation.builder()
                        .oldPath(nonExistentPath)
                        .newPath(relocationPath)
                        .build())
                status.flush()
            })

            assert this.relocationRepository.findAll().size() > 0

            // assert that db change is visible in other threads
            final Future<List<Relocation>> res = executor.submit(() -> {
                p "=> thread " + Thread.currentThread().name
                return tt.execute((TransactionStatus status) -> this.relocationRepository.findAll() as TransactionCallback<List<Relocation>>)
            } as Callable<List<Relocation>>)
            p res.get(2, TimeUnit.DAYS)
            p res.get().class
            assert !res.get().empty // size() > 0

            assert this.relocationRepository.findById(nonExistentPath).present
            final byId = this.relocationRepository.findById(nonExistentPath).get()
            assert byId.oldPath == nonExistentPath
            assert byId.newPath == relocationPath

            // re-get by url, this time no 404 should occur but rather 301 Re-Location:
            final resp = this.restTemplateNoRedirect.getForEntity(nonExistentUrl, String.class)
            assert resp.statusCode == HttpStatus.MOVED_PERMANENTLY
            final locationHeaders = resp.headers.get(HttpHeaders.LOCATION)
            assert locationHeaders.size() == 1
            assert locationHeaders.first() == url(relocationPath)
        }
    }

    @Test
    @Transactional
    void cacheToc() {
        final jt = this.jdbcTemplate

        final authors = jt.queryForList("select * from author")
        assert !authors.isEmpty()
        assert !jt.queryForList("select * from $TEI_ELEM").isEmpty()

        final divs = this.teiDivRepository.findAll()
        assert !divs.isEmpty()

        divs.forEach { p it }

        this.divService.getOpera(authors[0]['str_id']).forEach { p it }

        final cache = new DiskCache(this.cacheDir)

        final StopWatch watch = new StopWatch(); watch.start()
        final idTocs = divs.collect {
            final toc = new Toc(it)
            final id = it.id
            cache[id] = toc
            [id, toc]
        }
        watch.stop()
        p "took ${watch}"

        assert cache[idTocs.first()[0]] != null
    }

    String url(String path) {
        if (path.startsWith('/'))
            path = path.substring(1)

        return "http://localhost:${this.port}/$path"
    }

    @Test
    void testGetDotText() {
        assert divController != null

        // twice because we need to test also the cache behaviour
        2.times { time ->
            printHeader(time)

            final dotTxt = "/alecsandri/legende/dumbrava_rosie.txt"

            final responseEntity = tbc.getDefault_ResponseEntity(dotTxt)
            final resp = responseEntity.getBody()

            assert ! resp.empty
            assert resp.contains('Dumbrava roșie')
            assert !resp.contains('<')
            assert !resp.contains('>')

            assert responseEntity.headers['Content-Type'] == [ 'text/plain;charset=utf-8' ]
        }

        _2: {
            // assert expected \n  behaviour in .txt version
            final cucul = '/alecsandri/poezii_populare_ale_romanilor/cantece_batranesti/cucul_si_turturica'
            assert ! elemTxt(cucul, 1).empty // title
            assert elemTxt(cucul, 2) == 'CUCUL'

            elemTxt(cucul, 3).with {
                // make sure l lines in an lg are \n-separated in the txt version
                assert it.startsWith(' Dulce turturică,\nDalbă păsărică!\nHai să ne iubim')
                assert it.endsWith('Cântând împreună.') // make sure there is no \n at the end
            }

            // more assertions could be added for that note.

            elemTxt(cucul, 1).with {
                // this one has a footnote in title
                assert ! it.empty //
                assert it.startsWith("Cucul și turturica [Turturica")
            }

            elemTxt(cucul, 5).with {
                assert ! it.empty // this one has a footnote in text
                assert  it.contains('Și fermecătoare [Poporul crede')
            }
        }
    }

    def elemTxt(str, i) { tbc.getTextPlain("$str/_${i}") }
    def elemXml(str, i) { tbc.getTextXml("$str/_${i}")}

    @Test
    void testGetDotXml() {
        assert divController != null

        // twice because we need to test also the cache behaviour
        2.times { time ->
            printHeader(time)

            final dotTxt = "/alecsandri/legende/dumbrava_rosie.xml"
            final respEnt = this.tbc.getDefault_ResponseEntity(dotTxt)
            final resp = respEnt.body

            assert ! resp.empty
            assert resp.contains('Dumbrava roșie')
            assert resp.contains('<')
            assert resp.contains('>')
            assert resp.contains('<?xml version="1.0" encoding="UTF-8"?>')

            assert respEnt.headers['Content-Type'] == [ 'text/xml;charset=utf-8' ]
        }

        // check header

    }

    @Test
    void testGetDotHtml() {
        assert divController != null

        // twice because we need to test also the cache behaviour
        2.times { time ->
            printHeader(time)

            final dotHtmlPath = "/alecsandri/legende/dumbrava_rosie.html"
            final resp = this.tbc.getDefault(dotHtmlPath)

            p resp
            assert ! resp.empty
            assert resp.contains('Dumbrava roșie')
            assert resp.contains('<')
            assert resp.contains('>')
            assert resp.contains('<h3>Dumbrava roșie </h3>') // undecorate html mark
            assert ! resp.contains('<ul class="breadcrumbs">') // only appears in decorated html
        }
    }

    @Test
    void testDivsHaveLanguageProperlySet() {
        final divs = this.teiDivRepository.findAll()
        divs.each {
            assert it.lang != null
        }
    }

    /**
     * number selectors are telling a div to give you its n'th block (typically a p, in prose)
     */
    @Test
    void testNumberSelectors() {
        final descMoldoveiPath = '/cantemir/descrierea_moldovei'
        final path = descMoldoveiPath + '/partea_eclesiastica_si_literara/'

        final String cantemir = 'cantemir'
        assert this.authorRepository.findAll().strId.contains(cantemir)
        assert this.authorRepository.findByStrId(cantemir).strId == [ cantemir ]

        assert ! this.tbc.getTextPlain("${path}/_1").empty

        _1: {
            try {
                this.tbc.getTextPlain_Response("${path}/1")
                Assertions.fail('/1 is not valid path; /_1 would be correect')
            } catch (HttpClientErrorException e) {
                assert e.statusCode.value() == 404
            }

            final resp = this.tbc.getTextPlain_Response("${path}/_1")
            assert resp.statusCode.value() == 200
            assert !resp.body.empty

        }

        // paragraphs
        assert ! this.tbc.getTextXml("${path}/_1").empty // this is the <head>Partea eclesiastică și literară</head>
        assert ! this.tbc.getTextXml("${path}/_2").empty // this is the <div> <head> Despre religia Moldovenilor ....

        assert ! this.tbc.getDefault("${path}/_1").empty
        assert ! this.tbc.getDefault("${path}/_2").empty
        assert ! this.tbc.getTextHtml("${path}/_1").empty
        assert ! this.tbc.getTextHtml("${path}/_2").empty

        // sentences
        try {
            this.tbc.getTextPlain("${path}/_1/_1") // there is no such subelement there should be a 404
            Assertions.fail("nope")
        } catch (HttpClientErrorException e) {
            assert e.statusCode.value() == 404
        }

        // this should work since _2 is a div
        assert this.tbc.getTextXml("${path}/_2/_1")  =~ ' <lb/>Despre religia Moldovenilor</head>$'

        // this is a <p>
        assert this.tbc.getTextXml("${path}/_2/_2") =~ ' Osliado, Corza, Dașuba, Striba, Semargle și Mocoza.</p>$'

        // this is the next <p>
        assert this.tbc.getTextXml("${path}/_2/_3") =~ 'și aducându-i la Roma au jertfit împreună și acelora.</p>$'

    }


    protected void printHeader(time) {
        p "=" * 80
        p "= PASS ${time}"
        p "=" * 80
    }

    @Test
    void dataRest() {
        // assert that /api/drest/teiDivs works (automatically generated from a rather complex Entity)
        final List divs = this.tbc.get_api_drest_teiDivs()
        assert divs instanceof java.util.List
        assert !divs.empty
        p divs.size()

        divs[0].with { e ->
            assert e.teiFile == null
            assert e.teiRepo == null
            assert e.id != null
            assert !e.xpath.empty
            assert e.nth != null
            assert e.urlFragment != null
            assert e._url == null
            assert ! e.head.empty
            assert ! e.head.empty
            assert e.relativeRoot == null
        }
    }

    @Test
    void findOperaByLang() {
        assert ! this.teiDivRepository.findOpera(PageRequest.of(0, 10)).toList().empty
        assert ! this.teiDivRepository.findOperaByLang(Languages.RO, PageRequest.of(0, 10)).toList().empty
    }

    @Test
    void toc() {
        final opid = getAnOpus().id
        assert this.tbc.get_api_divs_id_toc(opid).length > 0
    }

    TeiDiv getAnOpus() {
        final opera = this.teiDivRepository.findOpera(PageRequest.of(0, 20)).toList()
        return opera.first()
    }

    @Test
    void destroyAllExistingAndReimportAllTeis() {
        this.adminService.destroyAllExistingAndReimportAllTeis(
                new NoWriter(), true)
    }
}
