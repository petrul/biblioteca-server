package ro.editii.scriptorium

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.Compression
import org.springframework.boot.web.server.WebServer
import org.springframework.boot.web.servlet.ServletRegistrationBean

import java.util.zip.GZIPInputStream

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * biblioteca-server compresses its responses itself, by default - for the
 * reader and for any client reaching the port without Caddy. The real
 * server.compression.* settings from application.properties are bound onto
 * an embedded Tomcat (the server Spring Boot runs), so this checks what goes
 * on the wire without starting the whole application:
 *
 *   response                                  Accept-Encoding: gzip   result
 *   ----------------------------------------  ----------------------  -----------
 *   JSON / HTML / text of 1 KB or more        yes                     gzip
 *   anything under 1 KB                       yes                     as it is
 *   images and other non-listed types         yes                     as it is
 *   anything                                  not sent                as it is
 */
class HttpCompressionTest {

    private static final String BIG_JSON = '[' + (0..<2000).collect { "{\"path\":\"ariosto_ludovico/orlando_furioso/canto_${it}\",\"leaf\":true}" }.join(',') + ']'

    private WebServer server

    private static Compression compressionFromApplicationProperties() {
        final props = new Properties()
        HttpCompressionTest.getResourceAsStream('/application.properties').withCloseable { props.load(it) }
        final compressionProps = props.findAll { k, v -> k.toString().startsWith('server.compression.') }
        assertTrue(compressionProps['server.compression.enabled'] == 'true', 'compression is on by default')
        // Cast: the source is itself Iterable, which Groovy would otherwise
        // take for Binder's list-of-sources constructor.
        return new Binder((ConfigurationPropertySource) new MapConfigurationPropertySource(compressionProps))
                .bind('server.compression', Compression).get()
    }

    @BeforeEach
    void start() {
        final factory = new TomcatServletWebServerFactory(0)
        factory.compression = compressionFromApplicationProperties()
        final servlet = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse res) {
                switch (req.requestURI) {
                    case '/toc': res.contentType = 'application/json'; res.writer.write(BIG_JSON); break
                    case '/page': res.contentType = 'text/html'; res.writer.write('<p>stanza</p>' * 500); break
                    case '/small': res.contentType = 'application/json'; res.writer.write('{"ok":true}'); break
                    case '/cover.png': res.contentType = 'image/png'; res.outputStream.write(new byte[50_000]); break
                }
            }
        }
        server = factory.getWebServer(new ServletRegistrationBean(servlet, '/*'))
        server.start()
    }

    @AfterEach
    void stop() { server?.stop() }

    private Map get(String path, String acceptEncoding) {
        final connection = (HttpURLConnection) new URL("http://127.0.0.1:${server.port}${path}").openConnection()
        if (acceptEncoding) connection.setRequestProperty('Accept-Encoding', acceptEncoding)
        final bytes = connection.inputStream.withCloseable { it.bytes }
        return [encoding: connection.getHeaderField('Content-Encoding'), bytes: bytes]
    }

    @Test
    void aLargeJsonResponseIsGzipped() {
        final response = get('/toc', 'gzip')
        assertEquals('gzip', response.encoding)
        assertTrue(response.bytes.length < BIG_JSON.length() / 5, "${response.bytes.length} B on the wire for ${BIG_JSON.length()} B")
        assertEquals(BIG_JSON, new GZIPInputStream(new ByteArrayInputStream(response.bytes)).text)
    }

    @Test
    void pagesAreGzippedToo() {
        assertEquals('gzip', get('/page', 'gzip').encoding)
    }

    @Test
    void smallResponsesImagesAndClientsNotAskingGetTheBytesAsTheyAre() {
        assertNull(get('/small', 'gzip').encoding, 'under 1 KB')
        assertNull(get('/cover.png', 'gzip').encoding, 'not a listed type')
        final plain = get('/toc', null)
        assertNull(plain.encoding, 'no Accept-Encoding')
        assertEquals(BIG_JSON.length(), plain.bytes.length)
    }
}
