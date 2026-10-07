package ro.editii.scriptorium.rest;

import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Derby has no MVCC: while the importer's sweep holds write locks, a
 * concurrent read (the catalog query behind /api/works and friends) can
 * time out waiting for them (SQLState 40XL1/40XL2). That is an expected,
 * transient condition while an import runs, not a server bug: answer
 * 503 + Retry-After with ONE warn line, so clients and crawlers retry.
 *
 * Without this advice the exception reaches the container, which logs
 * the whole Hibernate SQL plus a Derby client stack per hit and serves
 * a generic 500. Any other data access failure is rethrown unchanged
 * and keeps the default handling and logging.
 */
@RestControllerAdvice
@Log4j2
public class DbBusyAdvice {

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<String> dbBusy(DataAccessException error) {
        if (!RestUtil.isDbBusy(error)) {
            throw error;
        }
        final String summary = RestUtil.summarize(error);
        log.warn("database busy, serving 503: {}", summary);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(summary);
    }
}
