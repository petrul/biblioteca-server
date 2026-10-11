package ro.editii.scriptorium.kafka;

import ro.editii.scriptorium.dto.EnrichWorkDto;
import ro.editii.scriptorium.dto.OpusRemovedDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.dto.UserLoggedInDto;
import ro.editii.scriptorium.model.TeiDiv;

/**
 * anounces events like new opus imported when ready. The main prod
 * implementation should be kafka-based.
 */
public interface TextbaseEventsPublisher {
    void signalNewOpusImported(TeiDivDto div);

    void signalOpusReimported(TeiDivDto div);

    void signalOpusRemoved(OpusRemovedDto event);

    void signalUserLoggedIn(UserLoggedInDto event);

    /** A served work lacks enrichment (a cover); see CoverEnrichmentRequests. */
    void signalEnrichWork(EnrichWorkDto event);
}
