package ro.editii.scriptorium.service;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real Derby locks over a private in-memory database; no development data. */
class DerbyPruneLockOrderTest {
    @Test
    void catalogReadCompletesWhilePruneWaitsForTheFileBeforeDeletingAuthorLinks() throws Exception {
        String url = "jdbc:derby:memory:prune_" + UUID.randomUUID().toString().replace("-", "") + ";create=true";
        try (Connection setup = DriverManager.getConnection(url)) {
            try (var sql = setup.createStatement()) {
                sql.execute("create table tei_file (id bigint primary key)");
                sql.execute("create table file_authors (file_id bigint references tei_file(id), author_id bigint)");
                sql.execute("insert into tei_file values 1");
                sql.execute("insert into file_authors values (1, 2)");
                sql.execute("call SYSCS_UTIL.SYSCS_SET_DATABASE_PROPERTY('derby.locks.waitTimeout', '5')");
                sql.execute("call SYSCS_UTIL.SYSCS_SET_DATABASE_PROPERTY('derby.locks.deadlockTimeout', '2')");
            }
            CountDownLatch writerStarted = new CountDownLatch(1);
            CountDownLatch fileLocked = new CountDownLatch(1);
            try (Connection reader = DriverManager.getConnection(url);
                 var workers = Executors.newSingleThreadExecutor()) {
                reader.setAutoCommit(false);
                // Repeatable read deterministically retains the file S lock,
                // like the still-running catalog query in the reported cycle.
                reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try (var sql = reader.createStatement(); ResultSet row = sql.executeQuery("select id from tei_file where id=1")) {
                    assertTrue(row.next());
                }
                var deletion = workers.submit(() -> {
                    try (Connection writer = DriverManager.getConnection(url)) {
                        writer.setAutoCommit(false);
                        try (var sql = writer.createStatement()) {
                            writerStarted.countDown();
                            // Derby FOR UPDATE takes U, compatible with S;
                            // the service uses this no-op update for a real X.
                            assertEquals(1, sql.executeUpdate("update tei_file set id=id where id=1"));
                            fileLocked.countDown();
                            sql.executeUpdate("delete from file_authors where file_id=1");
                            sql.executeUpdate("delete from tei_file where id=1");
                            writer.commit();
                        } catch (Exception failure) {
                            writer.rollback();
                            throw failure;
                        }
                    }
                    return null;
                });
                assertTrue(writerStarted.await(2, TimeUnit.SECONDS));
                try {
                    assertFalse(fileLocked.await(250, TimeUnit.MILLISECONDS),
                            "writer must wait for the reader before touching author links");
                    // This read must not deadlock: the waiting writer has not
                    // yet taken X locks on file_authors.
                    try (var sql = reader.createStatement(); ResultSet rows = sql.executeQuery("select count(*) from file_authors where file_id=1")) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getInt(1));
                    }
                } finally {
                    reader.commit();
                }
                deletion.get(8, TimeUnit.SECONDS);
                try (var sql = setup.createStatement(); ResultSet rows = sql.executeQuery("select count(*) from tei_file")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
        } finally {
            try {
                DriverManager.getConnection(url.replace(";create=true", ";drop=true"));
            } catch (java.sql.SQLException dropped) {
                // Derby signals successful in-memory database removal as 08006.
                if (!"08006".equals(dropped.getSQLState())) throw dropped;
            }
        }
    }
}
