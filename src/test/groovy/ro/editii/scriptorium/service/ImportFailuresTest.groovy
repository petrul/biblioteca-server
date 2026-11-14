package ro.editii.scriptorium.service

import org.junit.jupiter.api.Test

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A failing TEI is retried after a back-off or when it changes - not every minute. */
class ImportFailuresTest {

    Instant now = Instant.parse('2026-11-14T12:00:00Z')
    ImportFailures failures = new ImportFailures([instant: { -> now }, getZone: { -> ZoneOffset.UTC }] as Clock)
    static final String F = '/fr/pline_le_jeune_lettres.tei.xml'

    @Test
    void aFailedFileIsHeldBackForAnHourThenTwoAndSoOn() {
        failures.failed(F, 1000L, new IllegalStateException('Row was already updated or deleted'))
        assert failures.holdBack(F, 1000L)
        now = now + Duration.ofMinutes(59)
        assert failures.holdBack(F, 1000L)
        now = now + Duration.ofMinutes(2)
        assert !failures.holdBack(F, 1000L) // the hour has passed: one more try

        failures.failed(F, 1000L, new IllegalStateException('again'))
        def f = failures.all()[0]
        assert f.attempts() == 2 && f.retryAt() == now + Duration.ofHours(2) && f.cause() == 'IllegalStateException: again'
        assert f.firstFailedAt() == Instant.parse('2026-11-14T12:00:00Z')
    }

    @Test
    void aChangedFileIsTriedAtOnce() {
        failures.failed(F, 1000L, new RuntimeException('broken'))
        assert !failures.holdBack(F, 2000L)
        failures.failed(F, 2000L, new RuntimeException('still broken'))
        assert failures.all()[0].attempts() == 1 // a new version starts over
    }

    @Test
    void successClearsAndOtherFilesAreUnaffected() {
        failures.failed(F, 1000L, new RuntimeException('x'))
        assert !failures.holdBack('/other.tei.xml', 1000L)
        failures.succeeded(F)
        assert !failures.holdBack(F, 1000L) && failures.all().isEmpty()
    }

    @Test
    void theBackOffDoublesUpToADay() {
        assert (1..7).collect { ImportFailures.backoff(it).toHours() } == [1L, 2L, 4L, 8L, 16L, 24L, 24L]
        assert ImportFailures.backoff(60).toHours() == 24
    }
}
