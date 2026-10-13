package db.migration

import groovy.transform.CompileStatic
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Collections get UUID ids and a kind (FAVORITES, USER, SEARCH): the rows of
 * DIV_COLLECTION / DIV_COLLECTION_ITEM (numeric ids, an IS_FAVORITES flag)
 * are copied into BOOK_COLLECTION / BOOK_COLLECTION_ITEM with fresh UUIDs and
 * the old tables dropped. A database that never had the old tables is left
 * for Hibernate to create the new ones.
 */
@CompileStatic
class V10__Collections_with_uuid_ids extends BaseJavaMigration {

    @Override
    void migrate(Context context) {
        final Connection c = context.connection
        final DatabaseMetaData md = c.metaData
        if (!tableExists(md, 'DIV_COLLECTION')) return

        c.createStatement().withCloseable { st ->
            if (!tableExists(md, 'BOOK_COLLECTION')) st.execute('''CREATE TABLE BOOK_COLLECTION (
                ID VARCHAR(36) NOT NULL PRIMARY KEY, OWNER_ID BIGINT NOT NULL, NAME VARCHAR(200) NOT NULL,
                KIND VARCHAR(16) NOT NULL, SEARCH_QUERY VARCHAR(500), SEARCH_PARAMS VARCHAR(4000), CREATED_AT TIMESTAMP)''')
            if (!tableExists(md, 'BOOK_COLLECTION_ITEM')) st.execute('''CREATE TABLE BOOK_COLLECTION_ITEM (
                ID VARCHAR(36) NOT NULL PRIMARY KEY, COLLECTION_ID VARCHAR(36) NOT NULL, KIND VARCHAR(16),
                DIV_PATH VARCHAR(255), FRAGMENT_START VARCHAR(255), FRAGMENT_END VARCHAR(255),
                SNIPPET VARCHAR(2000), SCORE FLOAT, SORT_ORDER INTEGER NOT NULL, ADDED_AT TIMESTAMP)''')
        }

        final Map<Long, String> ids = [:]
        c.createStatement().withCloseable { st ->
            st.executeQuery('SELECT ID, OWNER_ID, NAME, IS_FAVORITES, CREATED_AT FROM DIV_COLLECTION').withCloseable { ResultSet rs ->
                c.prepareStatement('INSERT INTO BOOK_COLLECTION (ID, OWNER_ID, NAME, KIND, CREATED_AT) VALUES (?, ?, ?, ?, ?)')
                        .withCloseable { PreparedStatement ins ->
                    while (rs.next()) {
                        final String uuid = UUID.randomUUID().toString()
                        ids[rs.getLong(1)] = uuid
                        ins.setString(1, uuid)
                        ins.setLong(2, rs.getLong(2))
                        ins.setString(3, rs.getString(3))
                        ins.setString(4, rs.getBoolean(4) ? 'FAVORITES' : 'USER')
                        ins.setTimestamp(5, rs.getTimestamp(5))
                        ins.executeUpdate()
                    }
                }
            }
        }

        if (tableExists(md, 'DIV_COLLECTION_ITEM')) {
            c.createStatement().withCloseable { st ->
                st.executeQuery('''SELECT COLLECTION_ID, KIND, DIV_PATH, FRAGMENT_START, FRAGMENT_END, ADDED_AT
                                   FROM DIV_COLLECTION_ITEM ORDER BY COLLECTION_ID, ADDED_AT, ID''').withCloseable { ResultSet rs ->
                    c.prepareStatement('''INSERT INTO BOOK_COLLECTION_ITEM (ID, COLLECTION_ID, KIND, DIV_PATH,
                            FRAGMENT_START, FRAGMENT_END, SORT_ORDER, ADDED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)''')
                            .withCloseable { PreparedStatement ins ->
                        final Map<String, Integer> next = [:]
                        while (rs.next()) {
                            final String collection = ids[rs.getLong(1)]
                            if (collection == null) continue
                            final int order = next.getOrDefault(collection, 0)
                            next[collection] = order + 1
                            ins.setString(1, UUID.randomUUID().toString())
                            ins.setString(2, collection)
                            ins.setString(3, rs.getString(2))
                            ins.setString(4, rs.getString(3))
                            ins.setString(5, rs.getString(4))
                            ins.setString(6, rs.getString(5))
                            ins.setInt(7, order)
                            ins.setTimestamp(8, rs.getTimestamp(6))
                            ins.executeUpdate()
                        }
                    }
                }
            }
        }

        c.createStatement().withCloseable { st ->
            if (tableExists(md, 'DIV_COLLECTION_ITEM')) st.execute('DROP TABLE DIV_COLLECTION_ITEM')
            st.execute('DROP TABLE DIV_COLLECTION')
        }
    }

    // Unquoted identifiers fold to upper case on Derby and lower case on
    // PostgreSQL - look under both.
    private static boolean tableExists(DatabaseMetaData metadata, String table) {
        [table, table.toLowerCase(Locale.ROOT)].any { String name ->
            metadata.getTables(null, null, name, ['TABLE'] as String[]).withCloseable { it.next() }
        }
    }
}
