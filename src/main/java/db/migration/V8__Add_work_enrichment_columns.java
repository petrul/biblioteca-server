package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/** Work enrichment's own fields on the opus envelope: summary, its source, when it last ran. */
public class V8__Add_work_enrichment_columns extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        final DatabaseMetaData metadata = context.getConnection().getMetaData();
        if (!tableExists(metadata, "TEI_OPUS")) return;
        final boolean derby = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("derby");
        final String add = derby ? "ALTER TABLE TEI_OPUS ADD COLUMN " : "ALTER TABLE TEI_OPUS ADD ";
        try (Statement statement = context.getConnection().createStatement()) {
            if (!columnExists(metadata, "TEI_OPUS", "SUMMARY")) statement.execute(add + "SUMMARY VARCHAR(1200)");
            if (!columnExists(metadata, "TEI_OPUS", "SUMMARY_SOURCE_URL")) statement.execute(add + "SUMMARY_SOURCE_URL VARCHAR(500)");
            if (!columnExists(metadata, "TEI_OPUS", "WORK_ENRICHED_AT")) statement.execute(add + "WORK_ENRICHED_AT TIMESTAMP");
        }
    }

    private static boolean columnExists(DatabaseMetaData metadata, String table, String column) throws Exception {
        try (ResultSet result = metadata.getColumns(null, null, table, column)) {
            if (result.next()) return true;
        }
        try (ResultSet result = metadata.getColumns(null, null,
                table.toLowerCase(Locale.ROOT), column.toLowerCase(Locale.ROOT))) {
            return result.next();
        }
    }

    private static boolean tableExists(DatabaseMetaData metadata, String table) throws Exception {
        try (ResultSet result = metadata.getTables(null, null, table, new String[]{"TABLE"})) {
            if (result.next()) return true;
        }
        try (ResultSet result = metadata.getTables(null, null, table.toLowerCase(Locale.ROOT), new String[]{"TABLE"})) {
            return result.next();
        }
    }
}
