package ro.editii.scriptorium;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs the Derby-compatible Flyway migrations against the application's
 * primary datasource before Hibernate starts managing the schema.
 *
 * The project deliberately uses flyway-core directly (rather than the Boot
 * starter), so the migration runner has to be declared explicitly.
 */
@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }
}
