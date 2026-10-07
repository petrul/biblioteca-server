package ro.editii.scriptorium.rest;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * The importer's write locks (Derby has no MVCC) can starve concurrent
 * reads into a lock timeout - SQLState 40XL1/40XL2. That must surface as
 * a retryable 503 with a one-line log, not a container 500 that dumps
 * the whole Hibernate SQL plus a Derby client stack per request.
 */
class DbBusyAdviceTest {

    private static final SQLException LOCK_TIMEOUT =
            new SQLException("ERROR 40XL1: A lock could not be obtained within the time requested", "40XL1");

    private static DataAccessException busyException() {
        return new JpaSystemException(new RuntimeException(LOCK_TIMEOUT));
    }

    @RestController
    static class ThrowingController {
        private final RuntimeException toThrow;

        ThrowingController(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @GetMapping("/api/works")
        String works() {
            throw toThrow;
        }
    }

    @Test
    void lockTimeoutIsServedAs503WithRetryAfter() throws Exception {
        standaloneSetup(new ThrowingController(busyException()))
                .setControllerAdvice(new DbBusyAdvice())
                .build().perform(get("/api/works"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(content().string(containsString("40XL1")));
    }

    @Test
    void otherDataAccessErrorsKeepDefaultHandling() {
        final DataAccessException other = new JpaSystemException(new RuntimeException("corrupt index"));

        final Exception thrown = assertThrows(Exception.class, () ->
                standaloneSetup(new ThrowingController(other))
                        .setControllerAdvice(new DbBusyAdvice())
                        .build().perform(get("/api/works")));

        // rethrown unchanged, so the original exception still reaches the
        // container with its default logging and 500
        boolean reachesContainer = false;
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause == other) {
                reachesContainer = true;
                break;
            }
        }
        assertTrue(reachesContainer, "a non-lock data access failure must not be swallowed by the advice");
    }

    @Test
    void detectionIsStateAndStatusBased() {
        assertTrue(RestUtil.isDbBusy(busyException()), "40XL1 in a wrapped chain is busy");
        assertTrue(RestUtil.isDbBusy(new JpaSystemException(
                        new RuntimeException(new SQLException("ERROR 40001: A lock could not be obtained due to a deadlock", "40001")))),
                "a deadlock victim (40001) is busy too");
        assertFalse(RestUtil.isDbBusy(new JpaSystemException(new RuntimeException("plain failure"))));
        assertTrue(RestUtil.isClientError(new ResponseStatusException(HttpStatus.NOT_FOUND, "no opus for [x/y]")));
        assertFalse(RestUtil.isClientError(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "boom")));
    }
}
