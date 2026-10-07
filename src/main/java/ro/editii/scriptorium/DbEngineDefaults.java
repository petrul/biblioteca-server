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
 * there, per profile - Derby (Network Server or the tests' embedded
 * memory URLs) or PostgreSQL. Neither can be hardcoded anymore:
 *
 * - The driver: Spring Boot's URL-prefix detection maps ANY jdbc:derby:
 *   URL (network or embedded) to org.apache.derby.iapi.jdbc.Autoloaded
 *   Driver, which is neither on this app's classpath (only derbyclient
 *   is) nor the right driver for a Network Server connection anyway.
 *
 * - The dialect: Hibernate 6+ moved DerbyDialect into the separate
 *   hibernate-community-dialects module (which is why auto-detection
 *   resolves PostgreSQL fine but Derby needs the explicit pointer).
 *
 * This processor maps the URL prefix to both values and installs them as
 * the lowest-precedence property source: any explicit value in a profile
 * file, system property or environment variable still wins. A URL with
 * an unrecognized prefix gets no defaults at all - Boot and Hibernate
 * then auto-detect, same as any plain Spring Boot app.
 */
public class DbEngineDefaults implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE = "dbEngineDefaults";

    private static final String DERBY_DRIVER = "org.apache.derby.client.ClientAutoloadedDriver";
    private static final String DERBY_DIALECT = "org.hibernate.community.dialect.DerbyDialect";
    private static final String POSTGRESQL_DRIVER = "org.postgresql.Driver";
    private static final String POSTGRESQL_DIALECT = "org.hibernate.dialect.PostgreSQLDialect";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        final String url = environment.resolvePlaceholders(
                environment.getProperty("spring.datasource.url", ""));

        final Map<String, Object> defaults = new HashMap<>();
        if (url.startsWith("jdbc:derby:")) {
            defaults.put("spring.datasource.driver-class-name", DERBY_DRIVER);
            defaults.put("spring.jpa.database-platform", DERBY_DIALECT);
        } else if (url.startsWith("jdbc:postgresql:")) {
            defaults.put("spring.datasource.driver-class-name", POSTGRESQL_DRIVER);
            defaults.put("spring.jpa.database-platform", POSTGRESQL_DIALECT);
        } else {
            return;
        }

        environment.getPropertySources()
                .addLast(new MapPropertySource(PROPERTY_SOURCE, defaults));
    }
}
