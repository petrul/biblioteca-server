package ro.editii.scriptorium;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
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

    @Bean
    static BeanFactoryPostProcessor flywayBeforeJpa() {
        return beanFactory -> {
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
