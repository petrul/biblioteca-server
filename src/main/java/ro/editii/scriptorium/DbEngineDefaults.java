package ro.editii.scriptorium;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Engine-aware defaults for the two properties Spring cannot derive from
 * the JDBC URL alone: the driver class and the Hibernate dialect.
 *
 * DB_URL (see application.properties) is engine-agnostic and simply rides
 * in from the environment, so which engine the app runs on is decided
 * there, per profile. The server runs on PostgreSQL only: Apache Derby,
 * the engine before Dusky Discus (2026-10-15), is retired - its driver is
 * no longer on the classpath and Flyway 10+ cannot migrate it - so a
 * jdbc:derby: URL stops the server at startup with that reason, instead of
 * a missing-driver error further on.
 *
 * This processor maps the URL prefix to the driver and dialect and installs them as
 * the lowest-precedence property source: any explicit value in a profile
 * file, system property or environment variable still wins. A URL with
 * an unrecognized prefix gets no defaults at all - Boot and Hibernate
 * then auto-detect, same as any plain Spring Boot app.
 */
public class DbEngineDefaults implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE = "dbEngineDefaults";

    private static final String POSTGRESQL_DRIVER = "org.postgresql.Driver";
    private static final String POSTGRESQL_DIALECT = "org.hibernate.dialect.PostgreSQLDialect";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        final String url = environment.resolvePlaceholders(
                environment.getProperty("spring.datasource.url", ""));

        final Map<String, Object> defaults = new HashMap<>();
        if (url.startsWith("jdbc:derby:")) {
            throw new IllegalStateException("DB_URL is an Apache Derby URL, but Derby is no longer supported - "
                    + "Biblioteca runs on PostgreSQL only (jdbc:postgresql://...)");
        }
        if (url.startsWith("jdbc:postgresql:")) {
            defaults.put("spring.datasource.driver-class-name", POSTGRESQL_DRIVER);
            defaults.put("spring.jpa.database-platform", POSTGRESQL_DIALECT);
        } else {
            return;
        }

        environment.getPropertySources()
                .addLast(new MapPropertySource(PROPERTY_SOURCE, defaults));
    }
}
