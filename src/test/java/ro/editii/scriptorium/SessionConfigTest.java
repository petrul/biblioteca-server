package ro.editii.scriptorium;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.servlet.Session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader's One Tap sign-in must persist 30 days: the servlet session it
 * establishes, and the JSESSIONID cookie the reader relays, must not fall
 * back to Tomcat's 30-minute, browser-session, in-memory defaults (the
 * cause of the One Tap prompt reappearing on reload). No Spring context.
 */
class SessionConfigTest {

    @Test
    void sessionsAndTheirCookieLastThirtyDaysAndAreStoredUnderTheWorkDir() throws Exception {
        Path workDir = Files.createTempDirectory("biblioteca-work");
        Session session = SessionConfig.persistentSession(workDir.toString(), false);

        assertEquals(Duration.ofDays(30), session.getTimeout(), "idle timeout");
        assertEquals(Duration.ofDays(30), session.getCookie().getMaxAge(), "cookie Max-Age: survives closing the browser");
        assertTrue(session.isPersistent(), "persisted across restarts");
        assertEquals(workDir.resolve("sessions").toFile(), session.getStoreDir());
        assertTrue(session.getStoreDir().isDirectory(), "store dir created");
        assertTrue(session.getCookie().getHttpOnly());
        assertFalse(session.getCookie().getSecure());
    }

    @Test
    void aTildeWorkDirResolvesToTheHomeDirectoryLikeTheRestOfTheServer() {
        // zmeu's dev profile sets WORK_DIR=~/.biblioteca, which Spring does not expand.
        String unique = ".biblioteca-session-test-" + System.nanoTime();
        Session session = SessionConfig.persistentSession("~/" + unique, true);
        File expected = new File(System.getProperty("user.home"), unique + File.separator + "sessions");
        try {
            assertEquals(expected, session.getStoreDir());
            assertTrue(session.getCookie().getSecure(), "secure flag passed through");
        } finally {
            expected.delete();
            expected.getParentFile().delete();
        }
    }
}
