package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Repairs databases which already contained TEI_OPUS when the disposable
 * compiled TEI tables were renamed by V6.  Derby can retain a foreign-key
 * constraint against the old TEI_ELEM table when both old and underscored
 * tables exist after a partial migration.
 */
public class V7__Repair_tei_opus_foreign_key extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        final DatabaseMetaData metadata = context.getConnection().getMetaData();
        if (!tableExists(metadata, "TEI_OPUS") || !tableExists(metadata, "_TEI_ELEM")) return;

        final List<String> staleConstraints = new ArrayList<>();
        try (ResultSet keys = metadata.getImportedKeys(null, null, "TEI_OPUS")) {
            while (keys.next()) {
                final String pkTable = keys.getString("PKTABLE_NAME");
                final String fkName = keys.getString("FK_NAME");
                if (fkName != null && !"_TEI_ELEM".equalsIgnoreCase(pkTable))
                    staleConstraints.add(fkName);
            }
        }

        try (Statement statement = context.getConnection().createStatement()) {
            for (String constraint : staleConstraints)
                statement.execute("ALTER TABLE TEI_OPUS DROP CONSTRAINT " + constraint);

            if (!hasReferenceTo(metadata, "TEI_OPUS", "_TEI_ELEM")) {
                statement.execute("ALTER TABLE TEI_OPUS ADD CONSTRAINT FK_TEI_OPUS_COMPILED_DIV "
                        + "FOREIGN KEY (TEI_DIV_ID) REFERENCES \"_tei_elem\" (ID)");
            }
        }
    }

    private static boolean hasReferenceTo(DatabaseMetaData metadata, String table, String target) throws Exception {
        try (ResultSet keys = metadata.getImportedKeys(null, null, table)) {
            while (keys.next())
                if (target.equalsIgnoreCase(keys.getString("PKTABLE_NAME"))) return true;
        }
        return false;
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
