package ro.editii.scriptorium.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import ro.editii.scriptorium.dto.EnrichWorkDto;
import ro.editii.scriptorium.dto.OpusRemovedDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.dto.UserLoggedInDto;

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
        fanOut("Biblioteca login", "Login: " + event.getUsername() + " (" + event.getProvider() + ") at " + event.getLoginAt());
    }

    @Override
    public void signalEnrichWork(EnrichWorkDto event) {
        this.kafkaProducer.sendAsJson(this.kafkaProps.getEnrichWorkTopicName(), event);
    }

    private void fanOut(String subject, String body) {
        // Keep the existing Biblioteca event even when the optional Greg
        // routing variables are absent (e.g. a local unit-test profile).
        if (isBlank(kafkaProps.getNotificationMailTo()) || isBlank(kafkaProps.getNotificationMailFrom())
                || isBlank(kafkaProps.getNotificationNtfyUrl())) {
            log.warn("Login notification fan-out is not configured (CONTACT_MAIL_TO/SMTP_FROM/NTFY_URL); skipping Greg topics");
            return;
        }
        final var mail = java.util.Map.of(
                "to", kafkaProps.getNotificationMailTo(),
                "from", kafkaProps.getNotificationMailFrom(),
                "subject", subject,
                "text", body);
        final var ntfy = new java.util.HashMap<String, Object>();
        ntfy.put("to", kafkaProps.getNotificationNtfyUrl());
        ntfy.put("from", kafkaProps.getNotificationMailFrom());
        ntfy.put("subject", subject);
        ntfy.put("body", body);
        ntfy.put("priority", "default");
        ntfy.put("tags", java.util.List.of("biblioteca"));
        try {
            kafkaProducer.sendAsJson(kafkaProps.getNotificationMailTopicName(), mail);
            kafkaProducer.sendAsJson(kafkaProps.getNotificationNtfyTopicName(), ntfy);
        } catch (RuntimeException failure) {
            // A notification backend must never turn a successful login into
            // a failed request. The existing Biblioteca login event has
            // already been published.
            log.error("Could not publish login notification fan-out", failure);
        }
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
}
