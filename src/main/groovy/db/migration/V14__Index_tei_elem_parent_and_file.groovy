package db.migration

import groovy.transform.CompileStatic
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

import java.sql.DatabaseMetaData

/** _tei_elem had no index on parent_id or tei_file_id, so every children
 *  lookup and every per-work query scanned the whole table: a large work's
 *  table of contents took 12-15 s (docs/caching-plan.md). The same names are
 *  declared on TeiElem, so ddl-auto creates them on a fresh database (no
 *  table yet when Flyway runs) and recognises them here. */
@CompileStatic
class V14__Index_tei_elem_parent_and_file extends BaseJavaMigration {
    static final Map<String, String> INDEXES = [
            idx_tei_elem_parent  : 'parent_id',
            idx_tei_elem_tei_file: 'tei_file_id',
    ]

    @Override
    void migrate(Context context) {
        final DatabaseMetaData metadata = context.connection.metaData
        if (!tableExists(metadata, '_TEI_ELEM')) return
        context.connection.createStatement().withCloseable { statement ->
            INDEXES.each { name, column ->
                statement.execute("CREATE INDEX IF NOT EXISTS $name ON _tei_elem ($column)".toString())
            }
        }
    }

    // Unquoted identifiers fold to upper case on H2, lower case on PostgreSQL.
    private static boolean tableExists(DatabaseMetaData metadata, String table) {
        for (String name : [table, table.toLowerCase(Locale.ROOT)]) {
            final boolean found = metadata.getTables(null, null, name, null).withCloseable { it.next() }
            if (found) return true
        }
        return false
    }
}
