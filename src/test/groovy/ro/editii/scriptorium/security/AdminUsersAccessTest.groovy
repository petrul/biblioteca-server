package ro.editii.scriptorium.security

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
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.security.google.FakeGoogleAuthTestConfig
import ro.editii.scriptorium.security.google.FakeGoogleIdTokenVerifier

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * ADMIN_USERS (admin.users) is a plain comma-separated allow-list - see
 * AdminUsers - not a real role/permission system, so its two actual jobs
 * (GET /api/users/me's isAdmin, driving the reader's admin page visibility;
 * /api/admin/** itself requiring being one of these users, not just signed
 * in - see SecurityConfig) are exactly what needs regression coverage:
 * get either one wrong and it's either an admin page nobody not-admin
 * should see, or real admin operations (reimport/reindex/prune) reachable
 * by any signed-in reader.
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:adminUsersAccessTestDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "admin.users=admin@example.com",
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TestConfig.class, FakeGoogleAuthTestConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminUsersAccessTest {

    @LocalServerPort int port
    @MockitoBean TextbaseEventsPublisher textbaseEventsPublisher

    final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    String url(String path) { "http://localhost:${this.port}${path}" }

    // Same sub for a given email across every test method in this shared
    // (PER_CLASS) context - GoogleAuthService.createFromGoogleAccount only
    // appends a disambiguating suffix to the username when the plain email
    // is already taken by a DIFFERENT sub, so reusing the same email with a
    // different sub per method (as an earlier version of this test did)
    // silently changes the real username out from under adminUsers.isAdmin's
    // exact-match check on every test after the first.
    private String signIn(String sub, String email) {
        final credential = FakeGoogleIdTokenVerifier.fakeToken(sub, email, email)
        final response = this.client.send(
                HttpRequest.newBuilder(URI.create(url("/api/auth/google")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("credential=" + URLEncoder.encode(credential, "UTF-8")))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
        response.headers().allValues("Set-Cookie").find { it.startsWith("JSESSIONID=") }.split(";")[0]
    }

    private HttpResponse<String> get(String path, String sessionCookie) {
        this.client.send(
                HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Accept", "*/*").header("Cookie", sessionCookie).GET().build(),
                HttpResponse.BodyHandlers.ofString())
    }

    private static final String ADMIN_SUB = "google-sub-admin"
    private static final String ADMIN_EMAIL = "admin@example.com"
    private static final String READER_SUB = "google-sub-reader"
    private static final String READER_EMAIL = "reader@example.com"

    @Test
    void whoAmIReportsIsAdminOnlyForAUsernameOnTheAdminUsersList() {
        final adminCookie = signIn(ADMIN_SUB, ADMIN_EMAIL)
        final readerCookie = signIn(READER_SUB, READER_EMAIL)

        assert get("/api/users/me", adminCookie).body().contains('"isAdmin":true')
        assert get("/api/users/me", readerCookie).body().contains('"isAdmin":false')
    }

    @Test
    void apiAdminEndpointsAreReachableForAnAdminUserButNotForAnOrdinarySignedInReader() {
        final adminCookie = signIn(ADMIN_SUB, ADMIN_EMAIL)
        final readerCookie = signIn(READER_SUB, READER_EMAIL)

        assert get("/api/admin/teirepos", adminCookie).statusCode() == 200
        assert get("/api/admin/teirepos", readerCookie).statusCode() == 403
    }

    @Test
    void apiAdminEndpointsAreNotReachableAnonymously() {
        final response = this.client.send(
                HttpRequest.newBuilder(URI.create(url("/api/admin/teirepos")))
                        .header("Accept", "*/*").GET().build(),
                HttpResponse.BodyHandlers.ofString())

        // formLogin() is enabled app-wide (see SecurityConfig), so an
        // unauthenticated request to any .authenticated()/.access()-guarded
        // path gets its entry point's redirect-to-login (302), not a bare
        // 401/403 - same as every other authenticated-only route in this
        // app, not specific to /api/admin.
        assert response.statusCode() == 302
    }

    @Test
    void apiAdminConfigIsTheOnePublicAdminEndpointForTheVectorizerStartupBootstrap() {
        // The deliberate /api/admin/config exception (see SecurityConfig):
        // biblioteca-nestjs fetches the shared naming convention at
        // startup with no credentials, so it must be reachable anonymously.
        // Every other /api/admin path stays gated - see the tests above.
        final response = this.client.send(
                HttpRequest.newBuilder(URI.create(url("/api/admin/config")))
                        .header("Accept", "*/*").GET().build(),
                HttpResponse.BodyHandlers.ofString())

        assert response.statusCode() == 200
        assert response.body().contains('"kafka"')
    }
}
