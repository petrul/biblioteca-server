package ro.editii.scriptorium.web;

import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ro.editii.scriptorium.tei.TeiResourceNotFoundException;

/**
 * Maps "source file no longer in TEI_REPOS" to a clean 404 with a
 * single-line warning, instead of the default 500 with a full stack
 * trace per request. DB rows for a file the corpus dropped (a rebuild
 * that renamed or removed it - pruneRemovedTeis cleans them up on its
 * next pass) otherwise flood the log with screenfuls of identical
 * stack traces whenever a reader or a crawler asks for that content.
 * A 404 is also the honest answer: that content's source is gone until
 * the stale rows are pruned or the file returns to the corpus.
 */
@RestControllerAdvice
@Log4j2
public class TeiSourceMissingAdvice {

    @ExceptionHandler(TeiResourceNotFoundException.class)
    public ResponseEntity<String> sourceMissing(TeiResourceNotFoundException e) {
        log.warn("content source no longer in TEI_REPOS, serving 404: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body("This content's source file is no longer available: " + e.getMessage());
    }
}
