package db.migration

import groovy.transform.CompileStatic
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

import java.sql.DatabaseMetaData

/** Relocations now record every division path a TEI reimport changes, and a
 *  deep path outgrows the 255 characters ddl-auto first gave the table; it
 *  never widens a column itself. The new reason/created_at columns are left
 *  to ddl-auto. A fresh database (no table yet) is ddl-auto's entirely. */
@CompileStatic
class V13__Widen_relocation_paths extends BaseJavaMigration {
    static final int PATH_LENGTH = 2000

    @Override
    void migrate(Context context) {
        final DatabaseMetaData metadata = context.connection.metaData
        final boolean postgres = metadata.databaseProductName.toLowerCase(Locale.ROOT).contains('postgres')
        context.connection.createStatement().withCloseable { statement ->
            for (String column : ['OLD_PATH', 'NEW_PATH']) {
                final Integer size = columnSize(metadata, 'RELOCATION', column)
                if (size == null || size >= PATH_LENGTH) continue
                statement.execute((postgres
                        ? "ALTER TABLE RELOCATION ALTER COLUMN $column TYPE VARCHAR($PATH_LENGTH)"
                        : "ALTER TABLE RELOCATION ALTER COLUMN $column SET DATA TYPE VARCHAR($PATH_LENGTH)").toString())
            }
        }
    }

    // null when the table or column is not there (a fresh database).
    // Unquoted identifiers fold to upper case on H2, lower case on PostgreSQL.
    private static Integer columnSize(DatabaseMetaData metadata, String table, String column) {
        for (List<String> names : [[table, column], [table.toLowerCase(Locale.ROOT), column.toLowerCase(Locale.ROOT)]]) {
            final Integer size = metadata.getColumns(null, null, names[0], names[1]).withCloseable {
                it.next() ? it.getInt('COLUMN_SIZE') : null
            }
            if (size != null) return size
        }
        return null
    }
}
