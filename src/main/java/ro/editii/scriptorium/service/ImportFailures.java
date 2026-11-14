package ro.editii.scriptorium.service;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEI files whose import failed, so the scheduled sweep does not retry them
 * every minute. reimportFresherTeis retries a file whenever it is newer than
 * its last successful import - for a file that always fails, that was every
 * minute, forever (2,646 times in three days, docs/caching-plan.md). A failed
 * file is retried when it changes, or after a back-off: 1 h, doubling, at most
 * 24 h. The admin's explicit reimport of one file is not held back.
 *
 * In memory: a restart retries everything once.
 */
@Component @Log4j2
public class ImportFailures {

    static final Duration FIRST_BACKOFF = Duration.ofHours(1);
    static final Duration MAX_BACKOFF = Duration.ofHours(24);

    /** A file's failed import: the file's modification time then, how often, the last cause, when to try again. */
    public record Failure(String filename, long fileModified, int attempts, String cause, Instant firstFailedAt, Instant retryAt) {}

    private final Map<String, Failure> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public ImportFailures() {
        this(Clock.systemUTC());
    }

    ImportFailures(Clock clock) {
        this.clock = clock;
    }

    /** True when the file failed as it is now and its back-off has not passed. */
    public boolean holdBack(String filename, long fileModified) {
        final Failure f = this.failures.get(filename);
        return f != null && f.fileModified() == fileModified && this.clock.instant().isBefore(f.retryAt());
    }

    public void failed(String filename, long fileModified, Throwable cause) {
        final Instant now = this.clock.instant();
        final Failure previous = this.failures.get(filename);
        final int attempts = previous != null && previous.fileModified() == fileModified ? previous.attempts() + 1 : 1;
        final Duration backoff = backoff(attempts);
        final String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        this.failures.put(filename, new Failure(filename, fileModified, attempts, message,
                attempts == 1 ? now : previous.firstFailedAt(), now.plus(backoff)));
        if (attempts == 1) {
            log.error("importing {} failed - next attempt in {} or when the file changes: {}", filename, backoff, message, cause);
        } else {
            log.warn("importing {} failed again ({} attempts) - next attempt in {}: {}", filename, attempts, backoff, message);
        }
    }

    public void succeeded(String filename) {
        if (this.failures.remove(filename) != null) log.info("{} imported, after earlier failures", filename);
    }

    public List<Failure> all() {
        return this.failures.values().stream().sorted(Comparator.comparing(Failure::firstFailedAt)).toList();
    }

    static Duration backoff(int attempts) {
        final Duration d = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts - 1, 10));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }
}
