package ro.editii.scriptorium.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import ro.editii.scriptorium.Globals;
import ro.editii.scriptorium.tei.TeiResourceNotFoundException;
import ro.editii.scriptorium.web.TeiSourceMissingAdvice;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real pooled connections, exclusively against a private Derby database. */
class PruneConnectionPoolTest {
    @Test
    void tenMissingSourceRequestsReleaseConnectionsWhileTheSchedulerOwnsTheImportLock() throws Exception {
        String url = "jdbc:derby:memory:pool_prune_" + UUID.randomUUID().toString().replace("-", "") + ";create=true";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(1000);
        // No repositories should be accessed when another prune owns the lock.
        // Null dependencies deliberately fail if the request goes past that guard.
        var advice = new TeiSourceMissingAdvice(new AdminService(null, null, null, null, null, null, null, null));
        CountDownLatch allConnectionsBorrowed = new CountDownLatch(10);
        CountDownLatch startCleanup = new CountDownLatch(1);

        try (var pool = new HikariDataSource(config);
             var requests = Executors.newFixedThreadPool(10)) {
            // Represents the scheduler's existing import/prune critical section.
            try (var scheduledPrune = Globals.lockImports()) {
                var responses = new ArrayList<Future<HttpStatus>>();
                for (int i = 0; i < 10; i++) {
                    responses.add(requests.submit(() -> {
                        // Models open-in-view retaining a connection until the
                        // error handler has returned and the HTTP request ends.
                        try (var requestConnection = pool.getConnection()) {
                            allConnectionsBorrowed.countDown();
                            if (!startCleanup.await(5, TimeUnit.SECONDS)) throw new AssertionError("cleanup never started");
                            return (HttpStatus) advice.sourceMissing(new TeiResourceNotFoundException("/gone.xml"))
                                    .getStatusCode();
                        }
                    }));
                }
                try {
                    assertTrue(allConnectionsBorrowed.await(5, TimeUnit.SECONDS), "all ten requests must occupy the pool");
                    assertEquals(10, pool.getHikariPoolMXBean().getActiveConnections());
                    startCleanup.countDown();
                    for (var response : responses) {
                        assertEquals(HttpStatus.NOT_FOUND, response.get(2, TimeUnit.SECONDS));
                    }
                    // The scheduler can now borrow a connection without releasing
                    // its import lock. Previously both sides waited for each other.
                    try (var schedulerConnection = pool.getConnection();
                         var sql = schedulerConnection.createStatement();
                         var result = sql.executeQuery("values 1")) {
                        assertTrue(result.next());
                        assertEquals(1, result.getInt(1));
                    }
                } finally {
                    startCleanup.countDown();
                }
            }
        } finally {
            try {
                DriverManager.getConnection(url.replace(";create=true", ";drop=true"));
            } catch (SQLException dropped) {
                if (!"08006".equals(dropped.getSQLState())) throw dropped;
            }
        }
    }

    @Test
    void importLockIsReentrantAndReleasedWhenAnOperationThrows() {
        assertThrows(IllegalStateException.class, () -> {
            try (var outer = Globals.lockImports(); var nested = Globals.lockImports()) {
                assertEquals(2, Globals.IMPORT_TEIS_WORKING.getHoldCount());
                throw new IllegalStateException("failed prune");
            }
        });
        assertFalse(Globals.IMPORT_TEIS_WORKING.isHeldByCurrentThread());
        try (var nextOperation = Globals.tryLockImports()) {
            assertNotNull(nextOperation);
        }
    }
}
