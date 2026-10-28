package db.migration

import groovy.transform.CompileStatic
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import ro.editii.scriptorium.model.AppUser

import java.sql.DatabaseMetaData

/**
 * Widens APP_USER.AVATAR_URL from Hibernate's default VARCHAR(255): Google's
 * photo URLs are far longer, and saving one failed the profile refresh with
 * "value too long". ddl-auto never widens an existing column.
 */
@CompileStatic
class V11__Widen_app_user_avatar_url extends BaseJavaMigration {

    @Override
    void migrate(Context context) {
        final DatabaseMetaData metadata = context.connection.metaData
        final Integer size = columnSize(metadata, 'APP_USER', 'AVATAR_URL')
        if (size == null || size >= AppUser.AVATAR_URL_MAX_LENGTH) return
        final boolean postgres = metadata.databaseProductName.toLowerCase(Locale.ROOT).contains('postgres')
        final String type = "VARCHAR(${AppUser.AVATAR_URL_MAX_LENGTH})"
        context.connection.createStatement().withCloseable { statement ->
            statement.execute((postgres
                    ? "ALTER TABLE APP_USER ALTER COLUMN AVATAR_URL TYPE $type"
                    : "ALTER TABLE APP_USER ALTER COLUMN AVATAR_URL SET DATA TYPE $type").toString())
        }
    }

    // The column's declared length, or null when the table or column is not
    // there yet (a fresh database - ddl-auto creates it at full width).
    // Unquoted identifiers fold to upper case on Derby/H2 and lower case on
    // PostgreSQL - look under both.
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
