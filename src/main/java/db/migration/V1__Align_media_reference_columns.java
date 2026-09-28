package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
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

        alterIfColumnExists(context, metadata, "MEDIA_REF", "URL", derby);
        alterIfColumnExists(context, metadata, "AUTHOR_MEDIA", "MEDIA_REF", derby);
        alterIfColumnExists(context, metadata, "DIV_MEDIA", "MEDIA_REF", derby);
    }

    private static void alterIfColumnExists(Context context, DatabaseMetaData metadata,
                                            String table, String column, boolean derby) throws Exception {
        if (!columnExists(metadata, table, column)) return;
        String sql = derby
                ? "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DATA TYPE VARCHAR(500)"
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
