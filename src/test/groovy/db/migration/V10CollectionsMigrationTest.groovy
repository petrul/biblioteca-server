package db.migration

import org.flywaydb.core.api.migration.Context
import org.junit.jupiter.api.Test

import java.sql.Connection
import java.sql.DriverManager

class V10CollectionsMigrationTest {

    @Test
    void copiesOldCollectionsToUuidTables() {
        final Connection c = DriverManager.getConnection("jdbc:h2:mem:v10migration;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
        c.createStatement().with {
            execute('CREATE TABLE DIV_COLLECTION (ID BIGINT PRIMARY KEY, OWNER_ID BIGINT, NAME VARCHAR(200), IS_FAVORITES BOOLEAN, CREATED_AT TIMESTAMP)')
            execute('CREATE TABLE DIV_COLLECTION_ITEM (ID BIGINT PRIMARY KEY, COLLECTION_ID BIGINT, KIND VARCHAR(20), DIV_PATH VARCHAR(255), FRAGMENT_START VARCHAR(255), FRAGMENT_END VARCHAR(255), ADDED_AT TIMESTAMP)')
            execute("INSERT INTO DIV_COLLECTION VALUES (1, 7, 'favorites', TRUE, CURRENT_TIMESTAMP)")
            execute("INSERT INTO DIV_COLLECTION VALUES (2, 7, 'mine', FALSE, CURRENT_TIMESTAMP)")
            execute("INSERT INTO DIV_COLLECTION_ITEM VALUES (1, 2, 'DIV', 'a/b', NULL, NULL, CURRENT_TIMESTAMP)")
            execute("INSERT INTO DIV_COLLECTION_ITEM VALUES (2, 2, 'FRAGMENT', 'a/b', '1.0', '1.5', CURRENT_TIMESTAMP)")
        }
        final Context context = [getConnection: { c }] as Context
        new V10__Collections_with_uuid_ids().migrate(context)

        def rows = c.createStatement().executeQuery('SELECT ID, KIND FROM BOOK_COLLECTION ORDER BY NAME')
        assert rows.next() && rows.getString(1).length() == 36 && rows.getString(2) == 'FAVORITES'
        assert rows.next() && rows.getString(2) == 'USER'
        def items = c.createStatement().executeQuery('SELECT SORT_ORDER, KIND FROM BOOK_COLLECTION_ITEM ORDER BY SORT_ORDER')
        assert items.next() && items.getInt(1) == 0 && items.getString(2) == 'DIV'
        assert items.next() && items.getInt(1) == 1 && items.getString(2) == 'FRAGMENT'
        assert !c.metaData.getTables(null, null, 'div_collection', null).next()
    }
}
