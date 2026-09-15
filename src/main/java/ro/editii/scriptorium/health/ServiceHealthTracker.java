package ro.editii.scriptorium.health;

import lombok.extern.log4j.Log4j2;

import java.time.Duration;
import java.time.Instant;

/**
 * A small circuit breaker: once markUnavailable() is called, isAvailable()
 * returns false for `cooldown` before automatically flipping back to true
 * on its own - no scheduled task, no active retry, just a timestamp
 * checked on demand. Meant for a dependency that's reachable-but-stuck
 * (a contended Ollama, say) rather than cleanly down: a plain
 * connection-refused already fails fast on its own every time, but
 * skipping the attempt entirely once we already know it's stuck turns a
 * guaranteed-to-timeout call (still correct, just slow) into an
 * immediate one, every time, until the cooldown elapses.
 *
 * Not itself responsible for detecting the failure or bounding the call -
 * callers still need their own timeout around the actual dependency call
 * (see Util.runWithTimeout) and to report the outcome here via
 * markUnavailable()/markAvailable().
 */
@Log4j2
public class ServiceHealthTracker {

    private final String serviceName;
    private final Duration cooldown;
    private volatile Instant unavailableSince;

    public ServiceHealthTracker(String serviceName, Duration cooldown) {
        this.serviceName = serviceName;
        this.cooldown = cooldown;
    }

    public boolean isAvailable() {
        final Instant since = this.unavailableSince;
        if (since == null) return true;
        if (Duration.between(since, Instant.now()).compareTo(this.cooldown) >= 0) {
            this.unavailableSince = null;
            log.info("{}: cooldown elapsed, will retry on the next call.", this.serviceName);
            return true;
        }
        return false;
    }

    public void markUnavailable() {
        if (this.unavailableSince == null) {
            this.unavailableSince = Instant.now();
            log.warn("{}: marked unavailable - will not be attempted again for {} minutes.",
                    this.serviceName, this.cooldown.toMinutes());
        }
    }

    /** How much longer until this auto-resets on its own - Duration.ZERO if already available. */
    public Duration remainingCooldown() {
        final Instant since = this.unavailableSince;
        if (since == null) return Duration.ZERO;
        final Duration elapsed = Duration.between(since, Instant.now());
        final Duration remaining = this.cooldown.minus(elapsed);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /** A successful call clears the flag immediately, even mid-cooldown - no reason to keep skipping once it's confirmed working again. */
    public void markAvailable() {
        this.unavailableSince = null;
    }
}
