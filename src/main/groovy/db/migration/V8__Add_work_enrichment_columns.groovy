package db.migration

import groovy.transform.CompileStatic
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

import java.sql.DatabaseMetaData

/** Work enrichment's own fields on the opus envelope: summary, its source, when it last ran. */
@CompileStatic
class V8__Add_work_enrichment_columns extends BaseJavaMigration {

    private static final Map<String, String> COLUMNS = [
            SUMMARY           : 'VARCHAR(1200)',
            SUMMARY_SOURCE_URL: 'VARCHAR(500)',
            WORK_ENRICHED_AT  : 'TIMESTAMP',
    ]

    @Override
    void migrate(Context context) {
        final DatabaseMetaData metadata = context.connection.metaData
        if (!tableExists(metadata, 'TEI_OPUS')) return
        final boolean derby = metadata.databaseProductName.toLowerCase(Locale.ROOT).contains('derby')
        final String add = derby ? 'ALTER TABLE TEI_OPUS ADD COLUMN ' : 'ALTER TABLE TEI_OPUS ADD '
        context.connection.createStatement().withCloseable { statement ->
            COLUMNS.each { column, type ->
                if (!columnExists(metadata, 'TEI_OPUS', column)) statement.execute("$add$column $type".toString())
            }
        }
    }

    // Unquoted identifiers fold to upper case on Derby and lower case on
    // PostgreSQL - look under both.
    private static boolean columnExists(DatabaseMetaData metadata, String table, String column) {
        [[table, column], [table.toLowerCase(Locale.ROOT), column.toLowerCase(Locale.ROOT)]].any { List<String> names ->
            metadata.getColumns(null, null, names[0], names[1]).withCloseable { it.next() }
        }
    }

    private static boolean tableExists(DatabaseMetaData metadata, String table) {
        [table, table.toLowerCase(Locale.ROOT)].any { String name ->
            metadata.getTables(null, null, name, ['TABLE'] as String[]).withCloseable { it.next() }
        }
    }
}
