package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Aligns the URL primary key and association foreign-key columns before
 * Hibernate validates the media relationships. Fresh databases are left
 * alone: Hibernate creates their tables with the corrected entity mapping.
 */
public class V1__Align_media_reference_columns extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        DatabaseMetaData metadata = context.getConnection().getMetaData();
        String product = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT);
        boolean derby = product.contains("derby");

        List<String> recreate = new ArrayList<>();
        try (ResultSet keys = metadata.getImportedKeys(null, null, "AUTHOR_MEDIA")) {
            while (keys.next()) {
                if ("MEDIA_REF".equalsIgnoreCase(keys.getString("PKTABLE_NAME"))) {
                    String name = keys.getString("FK_NAME");
                    String child = keys.getString("FKCOLUMN_NAME");
                    String parent = keys.getString("PKCOLUMN_NAME");
                    try (Statement s = context.getConnection().createStatement()) {
                        s.execute("ALTER TABLE AUTHOR_MEDIA DROP CONSTRAINT " + name);
                    }
                    recreate.add("ALTER TABLE AUTHOR_MEDIA ADD CONSTRAINT " + name
                            + " FOREIGN KEY (" + child + ") REFERENCES MEDIA_REF (" + parent + ")");
                }
            }
        }
        try (ResultSet keys = metadata.getImportedKeys(null, null, "DIV_MEDIA")) {
            while (keys.next()) {
                if ("MEDIA_REF".equalsIgnoreCase(keys.getString("PKTABLE_NAME"))) {
                    String name = keys.getString("FK_NAME");
                    String child = keys.getString("FKCOLUMN_NAME");
                    String parent = keys.getString("PKCOLUMN_NAME");
                    try (Statement s = context.getConnection().createStatement()) {
                        s.execute("ALTER TABLE DIV_MEDIA DROP CONSTRAINT " + name);
                    }
                    recreate.add("ALTER TABLE DIV_MEDIA ADD CONSTRAINT " + name
                            + " FOREIGN KEY (" + child + ") REFERENCES MEDIA_REF (" + parent + ")");
                }
            }
        }

        alterIfColumnExists(context, metadata, "MEDIA_REF", "URL", derby);
        alterIfColumnExists(context, metadata, "AUTHOR_MEDIA", "MEDIA_REF", derby);
        alterIfColumnExists(context, metadata, "DIV_MEDIA", "MEDIA_REF", derby);
        try (Statement s = context.getConnection().createStatement()) {
            for (String sql : recreate) s.execute(sql);
        }
    }

    private static void alterIfColumnExists(Context context, DatabaseMetaData metadata,
                                            String table, String column, boolean derby) throws Exception {
        if (!columnExists(metadata, table, column)) return;
        // PostgreSQL branch added by the postgres migration (same
        // product-branching pattern as V4): the legacy else-branch is
        // MySQL-only syntax. Derby behavior is unchanged.
        String product = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT);
        String sql = derby
                ? "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DATA TYPE VARCHAR(500)"
                : product.contains("postgres")
                ? "ALTER TABLE " + table + " ALTER COLUMN " + column + " TYPE VARCHAR(500)"
                : "ALTER TABLE " + table + " MODIFY COLUMN " + column + " VARCHAR(500)";
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean columnExists(DatabaseMetaData metadata, String table, String column) throws Exception {
        try (ResultSet result = metadata.getColumns(null, null, table, column)) {
            if (result.next()) return true;
        }
        try (ResultSet result = metadata.getColumns(null, null, table.toLowerCase(Locale.ROOT), column.toLowerCase(Locale.ROOT))) {
            return result.next();
        }
    }
}
