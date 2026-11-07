package ro.editii.scriptorium;

import javax.sql.DataSource;

import java.util.ServiceLoader;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



/**
 * Runs the engine-appropriate Flyway migrations against the application's
 * primary datasource before Hibernate starts managing the schema.
 *
 * The project deliberately uses flyway-core directly (rather than the Boot
 * starter), so the migration runner has to be declared explicitly. That
 * also means Boot's own "make the EntityManagerFactory depend on the
 * Flyway initializer" wiring is absent: nothing would otherwise stop
 * Hibernate's ddl-auto from creating the schema first, after which every
 * migration that guards on "does this table/column exist yet" (V1-V7 all
 * do, by design - fresh databases must be left alone) would see a fully
 * populated schema and run its repair branch against whatever engine the
 * URL selected - including the Derby-only ALTER/RENAME syntax on
 * PostgreSQL. The BeanFactoryPostProcessor below restores the same
 * dependsOn edge Boot would have wired: the entityManagerFactory bean
 * (and so ddl-auto) is not created until the flyway bean (and its
 * initMethod=migrate) has completed.
 *
 * The migrations are listed, not scanned for: every db.migration class
 * (Java V1-V7, Groovy V8 on) is named in
 * META-INF/services/org.flywaydb.core.api.migration.JavaMigration and
 * loaded with ServiceLoader. Classpath scanning does not work inside the
 * GraalVM native image ("unsupported protocol: resource" - Flyway then
 * finds no migration at all and refuses a schema that has them); a service
 * file does, and the JVM build uses the same list.
 * MigrationsListedTest fails when a db.migration class is missing from it.
 *
 * spring.flyway.enabled=false (on by default, see application.properties)
 * leaves the runner out entirely - the remaining Derby test context
 * (DerbyLobLocatorRegressionTest) needs that, since Flyway 10+ no longer
 * supports Derby; the other test contexts run the migrations on H2.
 */
@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    @ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(new String[0]) // nothing to scan: the migrations are listed (above)
                .javaMigrations(listedMigrations())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    /** Every migration named in the service file, in version order (Flyway sorts them too). */
    static JavaMigration[] listedMigrations() {
        return ServiceLoader.load(JavaMigration.class, FlywayConfig.class.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get)
                .toArray(JavaMigration[]::new);
    }

    @Bean
    static BeanFactoryPostProcessor flywayBeforeJpa() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition("flyway"))
                return;
            // Match the concrete FactoryBean class, not its product type:
            // EntityManagerFactory.class cannot be resolved from the bean
            // definition without instantiating the factory first, while
            // the definition's own factory-method return type
            // (LocalContainerEntityManagerFactoryBean, from Boot's own
            // JpaBaseConfiguration) is visible statically.
            for (String name : beanFactory.getBeanNamesForType(
                    org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean.class, false, false)) {
                // Matching the FactoryBean class yields the dereferenced
                // "&entityManagerFactory" form; the definition is registered
                // under the bare name.
                if (name.startsWith("&"))
                    name = name.substring(1);
                final BeanDefinition definition = beanFactory.getBeanDefinition(name);
                final String[] existing = definition.getDependsOn();
                final String[] dependsOn = new String[(existing == null ? 0 : existing.length) + 1];
                if (existing != null)
                    System.arraycopy(existing, 0, dependsOn, 0, existing.length);
                dependsOn[dependsOn.length - 1] = "flyway";
                definition.setDependsOn(dependsOn);
            }
        };
    }
}
