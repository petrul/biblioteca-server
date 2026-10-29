package ro.editii.scriptorium.kafka;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class KafkaProps {

    // Never actually overridden anywhere in real config - plain defaults
    // rather than Spring properties nobody sets (not final: tests
    // construct a KafkaProps directly and override these for isolation).
    String newOpusImportedTopicName = "biblioteca_newOpusImportedTopic";
    String opusReimportedTopicName = "biblioteca_opusReimportedTopic";
    String opusRemovedTopicName = "biblioteca_opusRemovedTopic";
    String loginTopicName = "biblioteca_loginTopic";
    String enrichWorkTopicName = "biblioteca_enrichWorkTopic";

    /**
     * Interesting human events (login, search, chat) as JSON - see
     * events.HumanEvents. A constant, never a setting or a secret; served to
     * biblioteca-nestjs through the shared config like the topics above.
     */
    String humanEventsTopicName = "biblioteca_events";

    // No inline default here on purpose, unlike before - it used to be
    // "kafka:9092", silently different from application.properties's own
    // spring.kafka.bootstrap-servers=${KAFKA_BROKERS} (which fails fast
    // with no default). A missing KAFKA_BROKERS must fail fast the same
    // way here too, not silently point this producer at a nonexistent
    // broker.
    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;
}
