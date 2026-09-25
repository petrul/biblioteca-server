package ro.editii.scriptorium.vector;

import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.editii.scriptorium.Util;

/**
 * Checked once, right after the app is fully up: does Milvus have the
 * collection this run needs and respond to it? Flips off vector similarity
 * search for the rest of this run if not, instead of every subsequent
 * search request failing with a raw gRPC exception.
 *
 * Deliberately does NOT also ping the embedder: biblioteca-server itself
 * essentially never embeds text at request time (that's batch work,
 * delegated to textbase-nestjs) - the only embedder call left on this
 * request path is none at all, so pinging Ollama here would just be an
 * unrelated dependency this check doesn't need, and one more thing that
 * can make startup slow/flaky if Ollama happens to be busy/contended.
 *
 * A likely reason this matters in practice: swapping the production
 * embedder (see VectorConfig) means the Milvus collection it expects
 * (named after that embedder, by convention) may not exist yet until
 * someone re-embeds the corpus with the new model - this lets the rest
 * of the app (DB-backed search, everything not vector-search-related) keep
 * working normally in the meantime instead of the whole app failing to
 * start or every /api/search/milvus and /api/search/ann call blowing up.
 *
 * A one-shot startup check alone also made the flag sticky in both
 * directions: a Milvus outage at boot kept search off forever (until the
 * next server restart), and an outage after boot left requests hitting a
 * dead Milvus (the one-shot snapshot stays true through it). Hence
 * recheckAvailability() below - the periodic counterpart that flips the
 * flag within one interval of Milvus going away or coming back, with no
 * server restart involved.
 */
@Component
@Log4j2
public class VectorSearchAvailability {

    // bounds the check below: a plain connection-refused fails fast on its
    // own, but Milvus being reachable-but-unresponsive (no gRPC deadline
    // set) could otherwise hang app startup indefinitely.
    private static final int CHECK_TIMEOUT_SECONDS = 15;

    private final MilvusService milvusService;
    private final MilvusCollection milvusCollection;
    private final Embedder embedder;

    @Value("${vector.search.availability-check.enabled:true}")
    private boolean availabilityCheckEnabled;

    private volatile boolean available = false;

    public VectorSearchAvailability(MilvusService milvusService, MilvusCollection milvusCollection, Embedder embedder) {
        this.milvusService = milvusService;
        this.milvusCollection = milvusCollection;
        this.embedder = embedder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkAvailability() {
        if (!this.availabilityCheckEnabled) {
            log.info("Vector similarity search availability check disabled.");
            this.available = false;
            return;
        }
        this.available = this.checkMilvus(true);

        if (this.available) {
            log.info("Vector similarity search available: milvus collection ({}) reachable.", this.milvusCollection.name);
        } else {
            log.warn("Vector similarity search DISABLED (milvus collection '{}' not reachable). "
                            + "/api/search/milvus and /api/search/ann will return empty results until the next periodic check picks it up.",
                    this.milvusCollection.name);
        }
    }

    /**
     * The periodic counterpart of the startup check above: Milvus gone
     * (restart, absence) flips vector search off within one interval,
     * Milvus back flips it on again - no server restart needed anymore.
     *
     * Transitions log exactly once per flip. A steadily-unavailable Milvus
     * logs nothing per cycle - a warn per minute of outage would be 1440
     * identical lines a day, exactly the log flood the retry loops in
     * biblioteca-nestjs pace their way around.
     *
     * Cadence: one interval is the worst-case window in which search
     * requests still go through to a dead Milvus (and degrade per-request
     * instead - see MilvusTextSearchService/ann()'s catch blocks), while
     * the check itself is two cheap gRPC reads. A minute is far off the
     * 15s import cycle's cadence on purpose: this state practically never
     * changes, so there is nothing to gain from checking it any faster.
     */
    @Scheduled(initialDelay = 60 * 1000, fixedDelay = 60 * 1000)
    public void recheckAvailability() {
        if (!this.availabilityCheckEnabled)
            return; // stays off; the startup check above already announced that

        final boolean wasAvailable = this.available;
        this.available = this.checkMilvus(false);

        if (this.available == wasAvailable)
            return;

        if (this.available) {
            log.info("Vector similarity search available again: milvus collection ({}) reachable.", this.milvusCollection.name);
        } else {
            log.warn("Vector similarity search now DISABLED (milvus collection '{}' unreachable since the last check) - "
                            + "/api/search/milvus and /api/search/ann will return empty results until the next check finds it back.",
                    this.milvusCollection.name);
        }
    }

    private boolean checkMilvus(boolean logOnFailure) {
        try {
            return Util.runWithTimeout(() -> this.milvusService.has(this.milvusCollection.name)
                    && this.milvusCollection.getVectorDimension() == this.embedder.vectorDimension(), CHECK_TIMEOUT_SECONDS);
        } catch (Exception e) {
            if (logOnFailure)
                log.warn("Milvus availability check failed (collection '{}'): {}", this.milvusCollection.name, e.getMessage());
            return false;
        }
    }

    public boolean isAvailable() {
        return this.available;
    }
}
