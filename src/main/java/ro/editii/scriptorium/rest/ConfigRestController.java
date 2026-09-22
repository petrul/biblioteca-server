package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.editii.scriptorium.dto.SharedConfigDto;
import ro.editii.scriptorium.kafka.KafkaProps;
import ro.editii.scriptorium.vector.Embedder;
import ro.editii.scriptorium.vector.MilvusCollection;
import ro.editii.scriptorium.vector.OllamaEmbedder;

/**
 * Deliberately its own (non-@Hidden) controller, not folded into
 * AdminRestController: that whole controller is @Hidden from the OpenAPI
 * spec (its other endpoints are internal/dangerous ops not meant for
 * public API docs), but this endpoint specifically needs to be visible so
 * textbase-nestjs's generated API client (see its gen-tb-api.sh) actually
 * picks it up -- textbase-nestjs is a dependent part of biblioteca-server,
 * not a peer, so it talks to it the same way any other API consumer does.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class ConfigRestController {

    final KafkaProps kafkaProps;
    // ObjectProvider, not a plain MilvusCollection/Embedder field: those
    // beans (see VectorConfig) require ${milvus.url}/${ollama.host} etc.
    // to actually resolve, which not every profile that boots this
    // controller (e.g. test profiles with no Milvus configured at all)
    // provides. A plain constructor dependency here would force their
    // eager construction the moment ANY test loads the full app context,
    // even ones with nothing to do with vectors -- ObjectProvider defers
    // that to when /config is actually called instead.
    final ObjectProvider<MilvusCollection> prodCollection;
    final ObjectProvider<Embedder> embedder;

    /**
     * The non-secret shared-resource naming convention every dependent
     * service (textbase-nestjs) must use to interoperate with this one:
     * Kafka topics, the Milvus collection paragraphs get vectorized into,
     * and which embedding model produced (and must be used to query) those
     * vectors. biblioteca-server is the source of truth for all three --
     * textbase-nestjs has no independent configuration of its own for any
     * of this, it fetches it from here at startup instead (see
     * TextbaseClient.getConfig() there). Deliberately excludes network
     * addresses (Kafka broker, Milvus host, Ollama host) -- those are each
     * service's own deployment/networking concern, not a naming
     * convention both sides need to agree on.
     */
    @GetMapping("/config")
    public SharedConfigDto config() {
        Embedder resolvedEmbedder = this.embedder.getIfAvailable();
        SharedConfigDto.Embedder embedderInfo;
        if (resolvedEmbedder != null) {
            SharedConfigDto.Embedder.EmbedderBuilder builder = SharedConfigDto.Embedder.builder()
                    .model(resolvedEmbedder.modelName())
                    .dimension(resolvedEmbedder.vectorDimension())
                    .description(resolvedEmbedder.describe());
            if (resolvedEmbedder instanceof OllamaEmbedder ollamaEmbedder) {
                builder.ollamaModel(ollamaEmbedder.getModel())
                        .host(ollamaEmbedder.getHost())
                        .port(ollamaEmbedder.getPort());
            }
            embedderInfo = builder.build();
        } else {
            embedderInfo = SharedConfigDto.Embedder.builder().build();
        }

        MilvusCollection resolvedCollection = this.prodCollection.getIfAvailable();
        SharedConfigDto.Milvus milvusInfo = SharedConfigDto.Milvus.builder()
                .collection(resolvedCollection != null ? resolvedCollection.getName() : null)
                .build();

        return SharedConfigDto.builder()
                .kafka(SharedConfigDto.Kafka.builder()
                        .newOpusImportedTopic(this.kafkaProps.getNewOpusImportedTopicName())
                        .opusReimportedTopic(this.kafkaProps.getOpusReimportedTopicName())
                        .opusRemovedTopic(this.kafkaProps.getOpusRemovedTopicName())
                        .build())
                .milvus(milvusInfo)
                .embedder(embedderInfo)
                .build();
    }
}
