package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.editii.scriptorium.dto.SharedConfigDto;
import ro.editii.scriptorium.kafka.KafkaProps;
import ro.editii.scriptorium.vector.Embedder;
import ro.editii.scriptorium.vector.VectorCollection;
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
    // ObjectProvider, not a plain VectorCollection/Embedder field: those
    // beans (see VectorConfig) require ${vectorstore.address}/${ollama.host}
    // etc. to actually resolve, which not every profile that boots this
    // controller (e.g. test profiles with no vector store configured at
    // all) provides. A plain constructor dependency here would force their
    // eager construction the moment ANY test loads the full app context,
    // even ones with nothing to do with vectors -- ObjectProvider defers
    // that to when /config is actually called instead. VectorCollection
    // (not MilvusCollection) so the exported name is whichever store is
    // active - qdrant by default, milvus behind vector.store=milvus.
    final ObjectProvider<VectorCollection> prodCollection;
    final ObjectProvider<Embedder> embedder;

    // Paragraph size window exported alongside the rest (see
    // application.properties's vectorizer.para.*) - plain non-final @Value
    // fields rather than constructor params: @RequiredArgsConstructor's
    // generated constructor wouldn't carry @Value annotations.
    @Value("${vectorizer.para.minChars:20}")
    int paraMinChars;
    @Value("${vectorizer.para.maxChars:3000}")
    int paraMaxChars;

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

        VectorCollection resolvedCollection = this.prodCollection.getIfAvailable();
        // The DTO field keeps its historic "milvus" name on the wire (the
        // deployed textbase-nestjs reads shared.milvus.collection) - what
        // it carries is simply "the collection name", for whichever store.
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
                .paragraph(SharedConfigDto.Paragraph.builder()
                        .minChars(this.paraMinChars)
                        .maxChars(this.paraMaxChars)
                        .build())
                .build();
    }
}
