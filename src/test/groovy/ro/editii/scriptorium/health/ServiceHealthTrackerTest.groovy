package ro.editii.scriptorium.health

import org.junit.jupiter.api.Test

import java.time.Duration

class ServiceHealthTrackerTest {

    @Test
    void availableByDefault() {
        final tracker = new ServiceHealthTracker('test', Duration.ofMinutes(15))
        assert tracker.isAvailable()
        assert tracker.remainingCooldown() == Duration.ZERO
    }

    @Test
    void markUnavailableFlipsItOffUntilTheCooldownElapses() {
        final tracker = new ServiceHealthTracker('test', Duration.ofMillis(200))
        tracker.markUnavailable()
        assert !tracker.isAvailable()
        assert tracker.remainingCooldown() > Duration.ZERO

        Thread.sleep(300)

        assert tracker.isAvailable()
        assert tracker.remainingCooldown() == Duration.ZERO
    }

    @Test
    void markAvailableClearsItImmediatelyEvenMidCooldown() {
        final tracker = new ServiceHealthTracker('test', Duration.ofMinutes(15))
        tracker.markUnavailable()
        assert !tracker.isAvailable()

        tracker.markAvailable()

        assert tracker.isAvailable()
        assert tracker.remainingCooldown() == Duration.ZERO
    }

    @Test
    void aSecondMarkUnavailableDoesNotResetTheClock() {
        final tracker = new ServiceHealthTracker('test', Duration.ofMillis(300))
        tracker.markUnavailable()
        Thread.sleep(150)
        tracker.markUnavailable() // must NOT push the deadline back out
        Thread.sleep(200)

        // 350ms have elapsed since the FIRST markUnavailable, past the
        // 300ms cooldown - already recovered, not still waiting on a
        // clock that a later markUnavailable call reset.
        assert tracker.isAvailable()
    }
}
