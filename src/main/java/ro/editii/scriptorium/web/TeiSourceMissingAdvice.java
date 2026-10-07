package ro.editii.scriptorium.web;

import lombok.extern.log4j.Log4j2;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ro.editii.scriptorium.tei.TeiResourceNotFoundException;
import ro.editii.scriptorium.service.AdminService;

/**
 * Maps "source file no longer in TEI_REPOS" to a clean 404 with a
 * single-line warning, instead of the default 500 with a full stack
 * trace per request. DB rows for a file the corpus dropped (a rebuild
 * that renamed or removed it - pruneRemovedTeis cleans them up on its
 * next pass, or this advice cleans them up immediately when the import/prune
 * lock is free, after confirming
 * absence from a ready, readable original repository) otherwise flood the log with screenfuls of identical
 * stack traces whenever a reader or a crawler asks for that content.
 * A 404 is also the honest answer: that content's source is gone until
 * the stale rows are pruned or the file returns to the corpus.
 */
@RestControllerAdvice
@Log4j2
@RequiredArgsConstructor
public class TeiSourceMissingAdvice {

    private final AdminService adminService;

    @ExceptionHandler(TeiResourceNotFoundException.class)
    public ResponseEntity<Void> sourceMissing(TeiResourceNotFoundException e) {
        try {
            adminService.pruneMissingTeiOnRequest(e.getResourceName());
        } catch (RuntimeException cleanupFailure) {
            // An unavailable repo or failed delete must never turn a missing
            // page into a 500; the scheduled sweep can retry the cleanup.
            log.warn("Could not clean up missing TEI source {}", e.getResourceName(), cleanupFailure);
        }
        log.warn("content source no longer in TEI_REPOS, serving 404: {}", e.getMessage());
        // Content requests may accept only XML/JSON (or already have that
        // content type set). An empty 404 needs no message conversion and
        // cannot fail while negotiating a plain-text error representation.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
