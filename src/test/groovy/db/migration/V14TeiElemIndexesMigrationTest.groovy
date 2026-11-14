package db.migration

import org.flywaydb.core.api.migration.Context
import org.junit.jupiter.api.Test

import java.sql.Connection
import java.sql.DriverManager

class V14TeiElemIndexesMigrationTest {

    @Test
    void indexesParentAndFileOnAnExistingTable_andIsRepeatable() {
        final Connection c = DriverManager.getConnection("jdbc:h2:mem:v14idx;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
        c.createStatement().execute('CREATE TABLE _TEI_ELEM (ID BIGINT PRIMARY KEY, PARENT_ID BIGINT, TEI_FILE_ID BIGINT)')
        final migration = new V14__Index_tei_elem_parent_and_file()

        migration.migrate([getConnection: { c }] as Context)
        // an index ddl-auto already made under the same name is left alone
        migration.migrate([getConnection: { c }] as Context)

        assert indexedColumns(c) == ['parent_id', 'tei_file_id'] as Set
    }

    @Test
    void leavesAFreshDatabaseToDdlAuto() {
        final Connection c = DriverManager.getConnection("jdbc:h2:mem:v14fresh;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
        new V14__Index_tei_elem_parent_and_file().migrate([getConnection: { c }] as Context)
        assert !c.metaData.getTables(null, null, '_tei_elem', null).next()
    }

    private static Set<String> indexedColumns(Connection c) {
        final Set<String> columns = [] as Set
        c.metaData.getIndexInfo(null, null, '_tei_elem', false, false).withCloseable {
            while (it.next()) if (it.getString('INDEX_NAME')?.startsWith('idx_tei_elem_')) columns << it.getString('COLUMN_NAME').toLowerCase()
        }
        columns
    }
}
