package ro.editii.scriptorium.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.editii.scriptorium.dto.EnrichWorkDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher;

/**
 * Asks for a cover when a work without one is served to the reader - the
 * catalog page, an author's works, the carousels and a single work card:
 * one EnrichWorkDto on biblioteca_enrichWorkTopic, which biblioteca-nestjs
 * answers by generating a random cover. Covers are never rendered on the
 * request path itself (the SEO og:image included).
 *
 * At most one request per work per RETRY_AFTER, so browsing the same pages
 * does not flood the topic while nestjs is still rendering (or failing).
 * Published off the request thread: the Kafka send blocks up to its timeout
 * and throws on failure, and neither may slow down or break a page.
 */
@Component
@Log4j2
public class CoverEnrichmentRequests {

    static final Duration RETRY_AFTER = Duration.ofMinutes(30);

    private final TextbaseEventsPublisher events;
    private final Clock clock;
    private final Executor executor;
    private final Map<String, Instant> lastRequested = new ConcurrentHashMap<>();

    @Autowired
    public CoverEnrichmentRequests(TextbaseEventsPublisher events) {
        this(events, Clock.systemUTC(), Executors.newSingleThreadExecutor(r -> {
            final Thread thread = new Thread(r, "cover-enrichment-requests");
            thread.setDaemon(true);
            return thread;
        }));
    }

    CoverEnrichmentRequests(TextbaseEventsPublisher events, Clock clock, Executor executor) {
        this.events = events;
        this.clock = clock;
        this.executor = executor;
    }

    /** Requests a cover for every work in `dtos` that is served without one. */
    public void requestMissing(Collection<? extends TeiDivDto> dtos) {
        if (dtos != null) dtos.forEach(this::requestMissing);
    }

    public void requestMissing(TeiDivDto dto) {
        if (dto == null || !dto.isOpus() || dto.getPath() == null) return;
        if (dto.getCoverUrl() != null && !dto.getCoverUrl().isBlank()) return;
        final String strId = dto.getPath().replaceFirst("^/+", "");
        final Instant now = this.clock.instant();
        final Instant previous = this.lastRequested.get(strId);
        if (previous != null && previous.plus(RETRY_AFTER).isAfter(now)) return;
        this.lastRequested.put(strId, now);
        this.executor.execute(() -> {
            try {
                this.events.signalEnrichWork(EnrichWorkDto.builder().strId(strId).build());
            } catch (RuntimeException e) {
                log.warn("could not request a cover for {}: {}", strId, e.getMessage());
                this.lastRequested.remove(strId); // retry on the next view
            }
        });
    }

    public void requestMissing(TeiDivDto[] dtos) {
        if (dtos != null) requestMissing(List.of(dtos));
    }
}
