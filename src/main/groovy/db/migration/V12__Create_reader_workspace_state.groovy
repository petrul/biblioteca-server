package db.migration

import groovy.transform.CompileStatic
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

import java.sql.DatabaseMetaData

/** Creates the versioned per-reader workspace/search state table.
 *  The two JSON columns are what Hibernate makes of @Lob String on each
 *  engine: OID on PostgreSQL (like author.bio, user_preference.preferences -
 *  its @Lob binding writes large objects), CLOB on H2, the tests' database.
 *  A plain CLOB stopped every PostgreSQL start here.
 *  On a fresh database (no APP_USER yet) it does nothing: ddl-auto creates
 *  the table with the dialect's types, like the other migrations assume. */
@CompileStatic
class V12__Create_reader_workspace_state extends BaseJavaMigration {
    @Override
    void migrate(Context context) {
        final DatabaseMetaData metadata = context.connection.metaData
        if (!tableExists(metadata, 'APP_USER') || tableExists(metadata, 'READER_WORKSPACE_STATE')) return
        final String lob = metadata.databaseProductName == 'PostgreSQL' ? 'OID' : 'CLOB'
        context.connection.createStatement().withCloseable { statement ->
            statement.execute("""
                CREATE TABLE READER_WORKSPACE_STATE (
                    ID BIGINT NOT NULL,
                    OWNER_ID BIGINT NOT NULL,
                    REVISION BIGINT NOT NULL,
                    LAST_SEARCH ${lob},
                    WORKSPACE ${lob},
                    UPDATED_AT TIMESTAMP NOT NULL,
                    CONSTRAINT PK_READER_WORKSPACE_STATE PRIMARY KEY (ID),
                    CONSTRAINT UK_READER_WORKSPACE_STATE_OWNER UNIQUE (OWNER_ID),
                    CONSTRAINT FK_READER_WORKSPACE_STATE_OWNER FOREIGN KEY (OWNER_ID) REFERENCES APP_USER(ID)
                )
            """.toString())
            statement.execute('CREATE SEQUENCE READER_WORKSPACE_STATE_SEQ START WITH 1 INCREMENT BY 50')
        }
    }

    // Unquoted identifiers fold to upper case on H2 and lower case on
    // PostgreSQL - look under both.
    private static boolean tableExists(DatabaseMetaData metadata, String table) {
        [table, table.toLowerCase(Locale.ROOT)].any { String name ->
            metadata.getTables(null, null, name, ['TABLE'] as String[]).withCloseable { it.next() }
        }
    }
}
