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
 * A work served to the reader that needs enrichment - never looked up, no
 * cover, or a cover whose object is gone - asks nestjs on
 * biblioteca_enrichWorkTopic, once per work per 30 minutes. Groovy
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

    final Set<String> missingObjects = []
    final List<String> probed = []
    final CoverEnrichmentRequests.CoverProbe probe = { String url -> probed << url; !missingObjects.contains(url) } as CoverEnrichmentRequests.CoverProbe

    // Runnable::run publishes synchronously, so the test sees the result at once.
    final CoverEnrichmentRequests requests = new CoverEnrichmentRequests(publisher, clock, { Runnable r -> r.run() } as java.util.concurrent.Executor, probe)

    private static TeiDivDto work(String path, String coverUrl, boolean opus, String workEnrichedAt = '2026-10-01T00:00:00Z') {
        def dto = new TeiDivDto()
        dto.path = path
        dto.coverUrl = coverUrl
        dto.opus = opus
        dto.workEnrichedAt = workEnrichedAt
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

    @Test
    void aWorkNeverLookedUpIsRequestedEvenWithACover() {
        requests.requestMissing(work('carlyle/past_and_present', 'https://x/biblioteca/covers/carlyle/p.png', true, null))
        assert published == ['carlyle/past_and_present']
        assert probed.isEmpty(): 'the request itself re-checks the cover in nestjs'
    }

    @Test
    void aStoredCoverWhoseObjectIsGoneIsRequestedAgain() {
        final gone = 'https://x/biblioteca/covers/abbott/greece-824fc4aaf727.png'
        missingObjects << gone
        requests.requestMissing([work('abbott/greece', gone, true), work('cehov/teatru', 'https://x/biblioteca/covers/cehov/t.png', true)])
        assert published == ['abbott/greece']
        assert probed.size() == 2
    }

    @Test
    void aServedCoverIsProbedAtMostEverySixHours() {
        final cover = work('cehov/teatru', 'https://x/biblioteca/covers/cehov/t.png', true)
        requests.requestMissing(cover)
        now = now + Duration.ofHours(5)
        requests.requestMissing(cover)
        assert probed.size() == 1
        now = now + Duration.ofHours(2)
        requests.requestMissing(cover)
        assert probed.size() == 2
        assert published.isEmpty()
    }
}
