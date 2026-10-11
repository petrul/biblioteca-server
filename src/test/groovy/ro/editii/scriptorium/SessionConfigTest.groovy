package ro.editii.scriptorium

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.time.Duration

/**
 * The reader's One Tap sign-in must persist 30 days: the servlet session it
 * establishes and the JSESSIONID the reader relays must not fall back to
 * Tomcat's 30-minute, browser-session, in-memory defaults. No Spring context.
 */
class SessionConfigTest {

    @Test
    void sessionsAndTheirCookieLastThirtyDaysAndAreStoredUnderTheWorkDir() {
        def workDir = Files.createTempDirectory('biblioteca-work')
        def session = SessionConfig.persistentSession(workDir.toString(), false)

        assert session.timeout == Duration.ofDays(30)
        assert session.cookie.maxAge == Duration.ofDays(30): 'cookie Max-Age: survives closing the browser'
        assert session.persistent: 'persisted across restarts'
        assert session.storeDir == workDir.resolve('sessions').toFile()
        assert session.storeDir.isDirectory()
        assert session.cookie.httpOnly
        assert !session.cookie.secure
    }

    @Test
    void theSecureFlagIsPassedThrough() {
        def session = SessionConfig.persistentSession(Files.createTempDirectory('biblioteca-work').toString(), true)
        assert session.cookie.secure
    }
}
