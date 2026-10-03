package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/** Adds the URL-only cover pointer written by the NestJS cover worker. */
public class V4__Add_opus_cover_url extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        DatabaseMetaData metadata = context.getConnection().getMetaData();
        if (!tableExists(metadata, "TEI_ELEM")) return;
        if (columnExists(metadata, "TEI_ELEM", "COVER_URL")) return;
        String product = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT);
        String sql = product.contains("derby")
                ? "ALTER TABLE TEI_ELEM ADD COLUMN COVER_URL VARCHAR(1000)"
                : "ALTER TABLE TEI_ELEM ADD COVER_URL VARCHAR(1000)";
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(sql);
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
