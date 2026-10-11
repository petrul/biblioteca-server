package ro.editii.scriptorium.service

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.dto.EnrichWorkDto
import ro.editii.scriptorium.dto.TeiDivDto
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * A work served to the reader without a cover asks nestjs for one on
 * biblioteca_enrichWorkTopic - once per work per 30 minutes. Groovy
 * map-coerced mocks, no Spring.
 */
class CoverEnrichmentRequestsTest {

    final List<String> published = []
    boolean kafkaDown = false
    final TextbaseEventsPublisher publisher = [
            signalEnrichWork: { EnrichWorkDto e ->
                if (kafkaDown) throw new IllegalStateException('kafka down')
                published << e.strId
            },
    ] as TextbaseEventsPublisher

    Instant now = Instant.parse('2026-10-11T10:00:00Z')
    final Clock clock = [instant: { now }, getZone: { ZoneOffset.UTC }] as Clock

    // Runnable::run publishes synchronously, so the test sees the result at once.
    final CoverEnrichmentRequests requests = new CoverEnrichmentRequests(publisher, clock, { Runnable r -> r.run() } as java.util.concurrent.Executor)

    private static TeiDivDto work(String path, String coverUrl, boolean opus) {
        def dto = new TeiDivDto()
        dto.path = path
        dto.coverUrl = coverUrl
        dto.opus = opus
        dto
    }

    @Test
    void requestsACoverOnlyForServedWorksThatLackOne() {
        requests.requestMissing([
                work('/eminescu/poezii', null, true),
                work('caragiale/momente', '  ', true),
                work('cehov/teatru', 'https://test.scriptorium.ro/biblioteca/covers/cehov/teatru-1.png', true),
                work('eminescu/poezii/luceafarul', null, false),
        ])
        assert published == ['eminescu/poezii', 'caragiale/momente']
    }

    @Test
    void asksAtMostOncePerWorkEveryThirtyMinutes() {
        def uncovered = work('eminescu/poezii', null, true)
        requests.requestMissing(uncovered)
        now = now + Duration.ofMinutes(29)
        requests.requestMissing(uncovered)
        assert published.size() == 1: 'throttled within 30 minutes'
        now = now + Duration.ofMinutes(2)
        requests.requestMissing(uncovered)
        assert published.size() == 2: 'asked again once the window passed'
    }

    @Test
    void aFailedPublishIsRetriedOnTheNextView() {
        def uncovered = work('eminescu/poezii', null, true)
        kafkaDown = true
        requests.requestMissing(uncovered)
        kafkaDown = false
        requests.requestMissing(uncovered)
        assert published == ['eminescu/poezii']
    }
}
