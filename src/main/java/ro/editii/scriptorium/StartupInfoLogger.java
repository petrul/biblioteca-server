package ro.editii.scriptorium;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Prints the reachable local URL before the runtime version summary. */
@Component
@Log4j2
public class StartupInfoLogger implements ApplicationListener<ApplicationReadyEvent> {
    private final VersionProperties versionProperties;
    private final Environment environment;

    public StartupInfoLogger(VersionProperties versionProperties, Environment environment) {
        this.versionProperties = versionProperties;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String port = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
        log.info("Listening on http://localhost:{}", port);
        log.info("SpringBoot          v{}", SpringBootVersion.getVersion());
        log.info("Biblioteca Server     v{}", versionProperties.getVersion());
    }
}
