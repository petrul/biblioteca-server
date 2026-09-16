package ro.editii.scriptorium.kafka;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ro.editii.scriptorium.dto.OpusRemovedDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.dto.UserLoggedInDto;

@RequiredArgsConstructor
@Component
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
    }
}
