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
 * Asks for enrichment when a work that needs it is served to the reader -
 * the catalog page, an author's works, the carousels and a single work
 * card: one EnrichWorkDto on biblioteca_enrichWorkTopic, which
 * biblioteca-nestjs answers by looking the work up on Wikipedia (once) and
 * generating a random cover. A work needs it when it was never looked up,
 * has no cover, or its stored cover URL no longer resolves (the MinIO
 * object was deleted) - the last checked off the request thread, at most
 * once per work per PROBE_AFTER. Covers are never rendered on the request
 * path itself (the SEO og:image included).
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
    static final Duration PROBE_AFTER = Duration.ofHours(6);

    /** Whether a stored cover URL still serves an image; false only on a definite miss. */
    @FunctionalInterface
    public interface CoverProbe {
        boolean exists(String coverUrl);
    }

    private final TextbaseEventsPublisher events;
    private final Clock clock;
    private final Executor executor;
    private final CoverProbe probe;
    private final Map<String, Instant> lastRequested = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastProbed = new ConcurrentHashMap<>();

    @Autowired
    public CoverEnrichmentRequests(TextbaseEventsPublisher events) {
        this(events, Clock.systemUTC(), Executors.newSingleThreadExecutor(r -> {
            final Thread thread = new Thread(r, "cover-enrichment-requests");
            thread.setDaemon(true);
            return thread;
        }), new HttpCoverProbe());
    }

    CoverEnrichmentRequests(TextbaseEventsPublisher events, Clock clock, Executor executor, CoverProbe probe) {
        this.events = events;
        this.clock = clock;
        this.executor = executor;
        this.probe = probe;
    }

    /** Requests enrichment for every work in `dtos` that needs it. */
    public void requestMissing(Collection<? extends TeiDivDto> dtos) {
        if (dtos != null) dtos.forEach(this::requestMissing);
    }

    public void requestMissing(TeiDivDto dto) {
        if (dto == null || !dto.isOpus() || dto.getPath() == null) return;
        final String strId = dto.getPath().replaceFirst("^/+", "");
        final String coverUrl = dto.getCoverUrl() == null || dto.getCoverUrl().isBlank() ? null : dto.getCoverUrl();
        if (coverUrl == null || dto.getWorkEnrichedAt() == null) {
            request(strId);
            return;
        }
        // Enriched and covered: only a vanished cover object needs a new request.
        final Instant now = this.clock.instant();
        final Instant probed = this.lastProbed.get(strId);
        if (probed != null && probed.plus(PROBE_AFTER).isAfter(now)) return;
        this.lastProbed.put(strId, now);
        this.executor.execute(() -> {
            if (!this.probe.exists(coverUrl)) {
                log.info("cover of {} is gone ({}); asking for a new one", strId, coverUrl);
                publish(strId);
            }
        });
    }

    private void request(String strId) {
        final Instant now = this.clock.instant();
        final Instant previous = this.lastRequested.get(strId);
        if (previous != null && previous.plus(RETRY_AFTER).isAfter(now)) return;
        this.lastRequested.put(strId, now);
        this.executor.execute(() -> publish(strId));
    }

    private void publish(String strId) {
        try {
            this.events.signalEnrichWork(EnrichWorkDto.builder().strId(strId).build());
        } catch (RuntimeException e) {
            log.warn("could not request enrichment for {}: {}", strId, e.getMessage());
            this.lastRequested.remove(strId); // retry on the next view
            this.lastProbed.remove(strId);
        }
    }

    /**
     * HEAD on the public cover URL. Only a 404 counts as missing - a timeout,
     * a 5xx or an unreachable host says nothing about the object, and must
     * not trigger a re-render.
     */
    static final class HttpCoverProbe implements CoverProbe {
        private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public boolean exists(String coverUrl) {
            try {
                final var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(coverUrl))
                        .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(10))
                        .build();
                return this.http.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() != 404;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            } catch (Exception e) {
                log.debug("cannot probe cover {}: {}", coverUrl, e.getMessage());
                return true;
            }
        }
    }

    public void requestMissing(TeiDivDto[] dtos) {
        if (dtos != null) requestMissing(List.of(dtos));
    }
}
