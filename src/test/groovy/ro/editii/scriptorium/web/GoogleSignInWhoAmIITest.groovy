package ro.editii.scriptorium.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.dao.AppUserRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.security.google.FakeGoogleAuthTestConfig
import ro.editii.scriptorium.security.google.FakeGoogleIdTokenVerifier

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Regression coverage for a real bug: signing in via Google worked
 * server-side, but the reader app's follow-up GET /api/users/me (the call
 * that actually drives the navbar avatar) came back 406 and was silently
 * read as "not signed in" - because a browser's plain fetch() sends a bare
 * wildcard Accept header, and WebConfig's defaultContentType(TEXT_HTML)
 * (meant only for browsable book pages) was hijacking that ambiguous
 * Accept for every JSON RestController endpoint too. Fixed by giving
 * defaultContentType a priority list (application/json first) instead of
 * a single hard default.
 *
 * This test exercises the whole real round trip an actual browser does -
 * POST a Google credential, then GET /api/users/me exactly the way a bare
 * fetch() would (a wildcard Accept, no other hints) - rather than testing
 * WebConfig's negotiation setting in isolation, so it would have caught
 * the bug exactly as it manifested.
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:googleSignInWhoAmIITestDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TestConfig.class, FakeGoogleAuthTestConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoogleSignInWhoAmIITest {

    @LocalServerPort int port
    @MockitoBean TextbaseEventsPublisher textbaseEventsPublisher

    final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    String url(String path) { "http://localhost:${this.port}${path}" }

    @Test
    void wholeGoogleSignInRoundTripSurvivesABareFetchAcceptHeader() {
        final credential = FakeGoogleIdTokenVerifier.fakeToken(
                "google-sub-whoami-test", "reader@example.com", "Test Reader", "https://example.com/avatar.jpg")

        final signInRequest = HttpRequest.newBuilder(URI.create(url("/api/auth/google")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("credential=" + URLEncoder.encode(credential, "UTF-8")))
                .build()
        final signInResponse = this.client.send(signInRequest, HttpResponse.BodyHandlers.ofString())

        assert signInResponse.statusCode() == 302
        assert signInResponse.headers().firstValue("Location").orElse("") == url("/")

        final sessionCookie = signInResponse.headers().allValues("Set-Cookie")
                .find { it.startsWith("JSESSIONID=") }
        assert sessionCookie != null

        // Exactly what a browser's bare `fetch('/api/users/me')` sends -
        // no explicit Accept, just the wildcard - this is the case that
        // used to 406.
        final whoAmIRequest = HttpRequest.newBuilder(URI.create(url("/api/users/me")))
                .header("Accept", "*/*")
                .header("Cookie", sessionCookie.split(";")[0])
                .GET().build()
        final whoAmIResponse = this.client.send(whoAmIRequest, HttpResponse.BodyHandlers.ofString())

        assert whoAmIResponse.statusCode() == 200
        assert whoAmIResponse.headers().firstValue("Content-Type").orElse("").startsWith("application/json")
        assert whoAmIResponse.body().contains('"authenticated":true')
        assert whoAmIResponse.body().contains('"username":"reader@example.com"')
        assert whoAmIResponse.body().contains('"avatarUrl":"https://example.com/avatar.jpg"')
    }

    @Test
    void anonymousWhoAmIWithABareFetchAcceptHeaderStillReturns200NotAuthenticated() {
        final request = HttpRequest.newBuilder(URI.create(url("/api/users/me")))
                .header("Accept", "*/*")
                .GET().build()
        final response = this.client.send(request, HttpResponse.BodyHandlers.ofString())

        assert response.statusCode() == 200
        assert response.body().contains('"authenticated":false')
    }

    @Test
    void aBrowsableBookPageStillDefaultsToDecoratedHtmlWithABareFetchAcceptHeader() {
        // Guards the ORIGINAL intent of WebConfig's defaultContentType -
        // must not regress while fixing the JSON-endpoint bug above.
        final request = HttpRequest.newBuilder(URI.create(url("/")))
                .header("Accept", "*/*")
                .GET().build()
        final response = this.client.send(request, HttpResponse.BodyHandlers.ofString())

        assert response.statusCode() == 200
        assert response.headers().firstValue("Content-Type").orElse("").startsWith("text/html")
    }
}
