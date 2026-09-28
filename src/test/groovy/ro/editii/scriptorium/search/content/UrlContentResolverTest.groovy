package ro.editii.scriptorium.search.content

import org.apache.commons.io.output.NullWriter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.service.AdminService

@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:derby:memory:myDb;create=true",
        "spring.datasource.driver-class-name=org.apache.derby.jdbc.EmbeddedDriver",
        "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TestConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
class UrlContentResolverTest {

    @LocalServerPort int port
    @Autowired UrlContentResolver urlContentResolver;
    @Autowired AdminService adminService

    @BeforeEach
    void beforeEach() {
        // the endpoint hit below needs its TEI imported first (starts out as an
        // empty DB otherwise) - just the one file this test actually needs,
        // not the full fixture set like WebITest imports
        adminService.reimportFile("ro/Alecsandri-Poezii.xml", new NullWriter())
    }

    @Test
    void resolve() {
        // points at this same test instance rather than the real production
        // textbase site, which isn't guaranteed to be up while testing
        final url = "http://localhost:${port}/alecsandri/legende/dumbrava_rosie"
        assert this.urlContentResolver.resolve(url) =~ /Dumbrava roșie/
    }
}