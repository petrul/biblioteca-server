package ro.editii.scriptorium.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import ro.editii.scriptorium.dto.EnrichWorkDto;
import ro.editii.scriptorium.dto.OpusRemovedDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.dto.UserLoggedInDto;
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A work served to the reader without a cover asks nestjs for one on
 * biblioteca_enrichWorkTopic - once per work per 30 minutes. No Spring.
 */
class CoverEnrichmentRequestsTest {

    static class RecordingPublisher implements TextbaseEventsPublisher {
        final List<String> enrich = new ArrayList<>();
        boolean fail;
        @Override public void signalNewOpusImported(TeiDivDto div) {}
        @Override public void signalOpusReimported(TeiDivDto div) {}
        @Override public void signalOpusRemoved(OpusRemovedDto event) {}
        @Override public void signalUserLoggedIn(UserLoggedInDto event) {}
        @Override public void signalEnrichWork(EnrichWorkDto event) {
            if (fail) throw new IllegalStateException("kafka down");
            enrich.add(event.getStrId());
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-11T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static TeiDivDto work(String path, String coverUrl, boolean opus) {
        TeiDivDto dto = new TeiDivDto();
        dto.setPath(path);
        dto.setCoverUrl(coverUrl);
        dto.setOpus(opus);
        return dto;
    }

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final MutableClock clock = new MutableClock();
    // Runnable::run publishes synchronously, so the test sees the result at once.
    private final CoverEnrichmentRequests requests = new CoverEnrichmentRequests(publisher, clock, Runnable::run);

    @Test
    void requestsACoverOnlyForServedWorksThatLackOne() {
        requests.requestMissing(List.of(
                work("/eminescu/poezii", null, true),
                work("caragiale/momente", "  ", true),
                work("cehov/teatru", "https://test.scriptorium.ro/biblioteca/covers/cehov/teatru-1.png", true),
                work("eminescu/poezii/luceafarul", null, false)));
        assertEquals(List.of("eminescu/poezii", "caragiale/momente"), publisher.enrich);
    }

    @Test
    void asksAtMostOncePerWorkEveryThirtyMinutes() {
        TeiDivDto uncovered = work("eminescu/poezii", null, true);
        requests.requestMissing(uncovered);
        clock.now = clock.now.plus(Duration.ofMinutes(29));
        requests.requestMissing(uncovered);
        assertEquals(1, publisher.enrich.size(), "throttled within 30 minutes");
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        requests.requestMissing(uncovered);
        assertEquals(2, publisher.enrich.size(), "asked again once the window passed");
    }

    @Test
    void aFailedPublishIsRetriedOnTheNextView() {
        TeiDivDto uncovered = work("eminescu/poezii", null, true);
        publisher.fail = true;
        requests.requestMissing(uncovered);
        publisher.fail = false;
        requests.requestMissing(uncovered);
        assertEquals(List.of("eminescu/poezii"), publisher.enrich);
    }
}
