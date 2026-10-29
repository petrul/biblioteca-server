package ro.editii.scriptorium.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import ro.editii.scriptorium.dto.EnrichWorkDto;
import ro.editii.scriptorium.dto.OpusRemovedDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.dto.UserLoggedInDto;
import ro.editii.scriptorium.events.HumanEvents;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
@Log4j2
public class KafkaTextbaseEventsPublisher implements TextbaseEventsPublisher {

    final KafkaProducer kafkaProducer;
    final KafkaProps kafkaProps;

    public void signalNewOpusImported(TeiDivDto div) {
        this.kafkaProducer.sendAsJson(this.kafkaProps.getNewOpusImportedTopicName(), div);
    };

    public void signalOpusReimported(TeiDivDto div) {
        this.kafkaProducer.sendAsJson(this.kafkaProps.getOpusReimportedTopicName(), div);
    }

    public void signalOpusRemoved(OpusRemovedDto event) {
        this.kafkaProducer.sendAsJson(this.kafkaProps.getOpusRemovedTopicName(), event);
    }

    public void signalUserLoggedIn(UserLoggedInDto event) {
        this.kafkaProducer.sendAsJson(this.kafkaProps.getLoginTopicName(), event);
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", event.getProvider());
        details.put("userId", event.getUserId());
        signalHumanEvent(HumanEvents.event("login", event.getUsername(), details));
    }

    @Override
    public void signalHumanEvent(Map<String, Object> event) {
        try {
            this.kafkaProducer.sendAsJson(this.kafkaProps.getHumanEventsTopicName(), event);
        } catch (RuntimeException failure) {
            // Recording an event must never fail the login, search or chat it records.
            log.error("Could not publish human event {}", event.get("type"), failure);
        }
    }

    @Override
    public void signalEnrichWork(EnrichWorkDto event) {
        this.kafkaProducer.sendAsJson(this.kafkaProps.getEnrichWorkTopicName(), event);
    }
}
