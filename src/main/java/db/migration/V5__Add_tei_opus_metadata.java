package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/** Creates the work-level metadata envelope without touching parsed TEI rows. */
public class V5__Add_tei_opus_metadata extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        final DatabaseMetaData metadata = context.getConnection().getMetaData();
        if (!tableExists(metadata, "TEI_ELEM")) return;
        if (tableExists(metadata, "TEI_OPUS")) return;
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("CREATE TABLE TEI_OPUS (" +
                    "ID BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY," +
                    "TEI_DIV_ID BIGINT NOT NULL UNIQUE," +
                    "DESCRIPTION VARCHAR(1200)," +
                    "SIGNIFICANT_QUOTE VARCHAR(1200)," +
                    "COVER_URL VARCHAR(1000)," +
                    "CONSTRAINT FK_TEI_OPUS_DIV FOREIGN KEY (TEI_DIV_ID) REFERENCES TEI_ELEM(ID))");
            // Preserve metadata written by older enrichment versions while
            // moving ownership to the opus envelope.  Fresh schemas simply
            // have no legacy values to copy.
            if (columnExists(metadata, "TEI_ELEM", "SUMMARY")) {
                statement.execute("INSERT INTO TEI_OPUS (TEI_DIV_ID, DESCRIPTION, COVER_URL) " +
                        "SELECT ID, SUMMARY, COVER_URL FROM TEI_ELEM WHERE PARENT_ID IS NULL");
            }
        }
    }

    private static boolean tableExists(DatabaseMetaData metadata, String table) throws Exception {
        try (ResultSet result = metadata.getTables(null, null, table, new String[]{"TABLE"})) {
            if (result.next()) return true;
        }
        try (ResultSet result = metadata.getTables(null, null, table.toLowerCase(), new String[]{"TABLE"})) {
            return result.next();
        }
    }

    private static boolean columnExists(DatabaseMetaData metadata, String table, String column) throws Exception {
        try (ResultSet result = metadata.getColumns(null, null, table, column)) {
            if (result.next()) return true;
        }
        try (ResultSet result = metadata.getColumns(null, null, table.toLowerCase(), column.toLowerCase())) {
            return result.next();
        }
    }
}
