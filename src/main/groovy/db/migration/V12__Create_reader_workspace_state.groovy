package db.migration

import groovy.transform.CompileStatic
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/** Creates the versioned per-reader workspace/search state table. */
@CompileStatic
class V12__Create_reader_workspace_state extends BaseJavaMigration {
    @Override
    void migrate(Context context) {
        context.connection.createStatement().withCloseable { statement ->
            statement.execute('''
                CREATE TABLE READER_WORKSPACE_STATE (
                    ID BIGINT NOT NULL,
                    OWNER_ID BIGINT NOT NULL,
                    REVISION BIGINT NOT NULL,
                    LAST_SEARCH CLOB,
                    WORKSPACE CLOB,
                    UPDATED_AT TIMESTAMP NOT NULL,
                    CONSTRAINT PK_READER_WORKSPACE_STATE PRIMARY KEY (ID),
                    CONSTRAINT UK_READER_WORKSPACE_STATE_OWNER UNIQUE (OWNER_ID),
                    CONSTRAINT FK_READER_WORKSPACE_STATE_OWNER FOREIGN KEY (OWNER_ID) REFERENCES APP_USER(ID)
                )
            '''.toString())
            statement.execute('CREATE SEQUENCE READER_WORKSPACE_STATE_SEQ START WITH 1 INCREMENT BY 50')
        }
    }
}
