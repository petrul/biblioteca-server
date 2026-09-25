package ro.editii.scriptorium.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** See ConfigRestController.config() for what this represents and why. */
@Data @AllArgsConstructor @Builder
public class SharedConfigDto {
    Kafka kafka;
    Milvus milvus;
    Embedder embedder;
    Paragraph paragraph;

    @Data @AllArgsConstructor @Builder
    public static class Kafka {
        String newOpusImportedTopic;
        String opusReimportedTopic;
        String opusRemovedTopic;
    }

    /**
     * Paragraph size window for vectorization - the application.properties
     * vectorizer.para.* pair: too-short paragraphs are skipped as noise,
     * too-long ones are truncated (not dropped) before embedding.
     */
    @Data @AllArgsConstructor @Builder
    public static class Paragraph {
        int minChars;
        int maxChars;
    }

    @Data @AllArgsConstructor @Builder
    public static class Milvus {
        String collection;
    }

    @Data @AllArgsConstructor @Builder
    public static class Embedder {
        /** canonical Milvus-collection-naming-convention identifier, e.g. "BGE_M3" */
        String model;
        /** vector dimension this embedder produces */
        int dimension;
        /** human-readable summary, also used as the Milvus collection's own description */
        String description;
        /** the actual Ollama model tag to call /api/embed with, e.g. "bge-m3" - null if not Ollama-backed */
        String ollamaModel;
        String host;
        Integer port;
    }
}
