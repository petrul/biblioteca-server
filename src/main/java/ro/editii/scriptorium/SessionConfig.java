package ro.editii.scriptorium;

import java.io.File;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sign-in sessions that last a month: the reader's Google One Tap sign-in
 * (GoogleAuthController) establishes this servlet session, and the reader
 * relays its JSESSIONID cookie. Tomcat's defaults - a 30-minute idle
 * timeout, a browser-session cookie and sessions kept only in memory - made
 * every visit after half an hour, a browser restart or a redeploy land
 * signed out, with the One Tap prompt showing again on reload.
 *
 * Sessions are persisted under work.dir/sessions (Tomcat writes them on a
 * graceful shutdown and reloads them on start), so they survive restarts
 * as long as the work directory is persistent - it is a volume in prod.
 */
@Configuration
public class SessionConfig {

    public static final Duration SESSION_LIFETIME = Duration.ofDays(30);

    /** The servlet session settings: 30-day idle timeout, persisted, with a 30-day cookie. */
    public static Session persistentSession(String workDir, boolean secureCookie) {
        Session session = new Session();
        session.setTimeout(SESSION_LIFETIME);
        session.setPersistent(true);
        File storeDir = new File(Util.replaceTilde(workDir), "sessions");
        storeDir.mkdirs();
        session.setStoreDir(storeDir);
        session.getCookie().setMaxAge(SESSION_LIFETIME);
        session.getCookie().setHttpOnly(true);
        session.getCookie().setSecure(secureCookie);
        return session;
    }

    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> persistentSessions(
            @Value("${work.dir}") String workDir,
            @Value("${biblioteca.session.cookie-secure:false}") boolean secureCookie) {
        return factory -> factory.setSession(persistentSession(workDir, secureCookie));
    }
}
