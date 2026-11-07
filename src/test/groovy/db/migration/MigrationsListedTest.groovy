package db.migration

import org.flywaydb.core.api.migration.JavaMigration
import org.junit.jupiter.api.Test
import ro.editii.scriptorium.FlywayConfig

/**
 * Flyway runs exactly the migrations named in
 * META-INF/services/org.flywaydb.core.api.migration.JavaMigration (the
 * GraalVM native image cannot scan the classpath for them - FlywayConfig):
 * a db.migration class missing from that file would silently never run.
 */
class MigrationsListedTest {

    @Test
    void every_db_migration_class_is_listed_and_loads_in_version_order() {
        final List<String> onDisk = ['src/main/java/db/migration', 'src/main/groovy/db/migration']
                .collectMany { new File(it).listFiles()?.toList() ?: [] }
                .collect { File f -> f.name }
                .findAll { String name -> name ==~ /V\d+__.+\.(java|groovy)/ }
                .collect { String name -> 'db.migration.' + name.replaceFirst(/\.(java|groovy)$/, '') }
        final List<String> listed = FlywayConfig.listedMigrations().collect { JavaMigration m -> m.class.name }

        assert onDisk.size() >= 11
        assert listed.toSorted() == onDisk.toSorted()
        assert FlywayConfig.listedMigrations().collect { JavaMigration m -> m.version.version as int } == (1..onDisk.size()).toList()
    }
}
