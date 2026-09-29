package ro.editii.scriptorium.web

import org.apache.commons.io.output.NullWriter
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.service.AdminService

import static ro.editii.scriptorium.TestUtils.TEI_ELEM

// Split out of WebITest (was davExportIsReachableThroughSecurityAndServesCorpusContent()
// there): the only test in that class exercising the WebDAV export mount rather than
// the ordinary teidiv/reader paths. It still needs the shared testrepo fixture
// imported (the DAV mount walks real author/opus/div content), but none of
// WebITest's Lucene-search isolation (@DynamicPropertySource index dir, the
// lucene.incremental.enabled=true override) - build.gradle's tasks.withType(Test)
// already leaves indexing off by default, which is all this class needs.
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:derby:memory:davItestDb;create=true",
        "spring.datasource.driver-class-name=org.apache.derby.iapi.jdbc.AutoloadedDriver",
        "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create"
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TestConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DavItest {

    @LocalServerPort int port
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired AdminService adminService

    @MockitoBean TextbaseEventsPublisher textbaseEventsPublisher

    @BeforeAll
    void beforeAll() {
        TestUtils.truncateAllTables(this.jdbcTemplate)
        this.adminService.reimportAllTeis(new NullWriter())

        assert TestUtils.countTableRows(this.jdbcTemplate, "author") > 0
        assert TestUtils.countTableRows(this.jdbcTemplate, "tei_file_authors") > 0
        assert TestUtils.countTableRows(this.jdbcTemplate, TEI_ELEM) > 0
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
}
