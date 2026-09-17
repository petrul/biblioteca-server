package ro.editii.scriptorium.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfiguration {

    @Autowired
    KafkaProps kafkaProps;

    @Bean
    public ProducerFactory<String, String> tbKafkaProducerFactory() {
        final var topics = this.kafkaProps;
        final Map<String, Object> props = new HashMap<>() {{
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    StringSerializer.class);
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    StringSerializer.class);
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    topics.getBootstrapServers());
        }};

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> kafkaProducerFactory) {
        return new KafkaTemplate<>(kafkaProducerFactory);
    }

//    @Bean
//    public ConsumerFactory<String, String>
//    hbfKafkaConsumerFactory() {
//        Map<String, Object> props = new HashMap<>();
//        props.put(
//                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
//                this.kafkaConstants.getBootstrapServers());
//        props.put(
//                ConsumerConfig.GROUP_ID_CONFIG,
//                this.kafkaConstants.getConsumerGroupId());
//        props.put(
//                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
//                StringDeserializer.class);
//        props.put(
//                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
//                StringDeserializer.class);
//        return new DefaultKafkaConsumerFactory<>(props);
//    }

//    @Bean
//    public ConcurrentKafkaListenerContainerFactory<String, String>
//    kafkaListenerContainerFactory(ConsumerFactory<String, String> hbfKafkaConsumerFactory) {
//
//        ConcurrentKafkaListenerContainerFactory<String, String> factory =
//                new ConcurrentKafkaListenerContainerFactory<>();
//        factory.setConsumerFactory(hbfKafkaConsumerFactory);
//        return factory;
//    }

    @Bean
    public NewTopic newOpus() {
        return TopicBuilder.name(this.kafkaProps.getNewOpusImportedTopicName()).build();
    }

    @Bean
    public NewTopic reimportOpus() {
        return TopicBuilder.name(this.kafkaProps.getOpusReimportedTopicName()).build();
    }


    @Bean
    public NewTopic removeOpus() {
        return TopicBuilder.name(this.kafkaProps.getOpusRemovedTopicName()).build();
    }
}
