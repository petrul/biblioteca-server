package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/** Marks imported TEI tables as disposable compiled state. */
public class V6__Rename_compiled_tei_tables extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            rename(context.getConnection().getMetaData(), statement, "TEI_FILE_AUTHORS", "_TEI_FILE_AUTHORS");
            rename(context.getConnection().getMetaData(), statement, "TEI_ELEM", "_TEI_ELEM");
            rename(context.getConnection().getMetaData(), statement, "TEI_FILE", "_TEI_FILE");
        }
    }

    private static void rename(DatabaseMetaData metadata, Statement statement, String oldName, String newName) throws Exception {
        if (!tableExists(metadata, oldName) || tableExists(metadata, newName)) return;
        statement.execute("RENAME TABLE " + oldName + " TO " + newName);
    }

    private static boolean tableExists(DatabaseMetaData metadata, String table) throws Exception {
        try (ResultSet result = metadata.getTables(null, null, table, new String[]{"TABLE"})) {
            if (result.next()) return true;
        }
        try (ResultSet result = metadata.getTables(null, null, table.toLowerCase(), new String[]{"TABLE"})) {
            return result.next();
        }
    }
}
