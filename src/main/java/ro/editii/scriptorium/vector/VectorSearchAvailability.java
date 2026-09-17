package ro.editii.scriptorium.vector;

import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
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
 * someone re-embeds the corpus with the new model - this lets the rest of
 * the app (DB-backed search, everything not vector-search-related) keep
 * working normally in the meantime instead of the whole app failing to
 * start or every /api/search/milvus and /api/search/ann call blowing up.
 *
 * This is a one-shot startup check, not a continuous health monitor - a
 * recovery (e.g. the collection gets created later) needs a restart to be
 * picked up.
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
        this.available = checkMilvus();

        if (this.available) {
            log.info("Vector similarity search available: milvus collection ({}) reachable.", this.milvusCollection.name);
        } else {
            log.warn("Vector similarity search DISABLED for this run (milvus collection '{}' not reachable). "
                            + "/api/search/milvus and /api/search/ann will return empty results until the next restart.",
                    this.milvusCollection.name);
        }
    }

    private boolean checkMilvus() {
        try {
            return Util.runWithTimeout(() -> this.milvusService.has(this.milvusCollection.name)
                    && this.milvusCollection.getVectorDimension() == this.embedder.vectorDimension(), CHECK_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("Milvus availability check failed (collection '{}'): {}", this.milvusCollection.name, e.getMessage());
            return false;
        }
    }

    public boolean isAvailable() {
        return this.available;
    }
}
