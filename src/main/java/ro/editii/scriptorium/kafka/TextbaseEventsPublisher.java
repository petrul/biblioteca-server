package ro.editii.scriptorium.kafka;

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

    void signalUserLoggedIn(UserLoggedInDto event);
}
