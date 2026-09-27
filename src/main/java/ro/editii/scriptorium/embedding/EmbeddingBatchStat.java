package ro.editii.scriptorium.embedding;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;

import java.time.Instant;

/**
 * One embedding batch's cost, as reported by whichever worker actually
 * called the encoder (biblioteca-nestjs's VectorizerService, today) - not
 * this server's own concern, since it never calls an embedder itself.
 * Recorded purely for capacity planning: embedding is real, non-trivial
 * GPU/CPU time, and knowing the actual ms-per-batch and ms-per-KB-of-text
 * at the model/encoder currently in use is what makes "how long will
 * revectorizing the whole corpus take" an answerable question instead of
 * a guess.
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Comment("One embedding batch's timing, reported by whichever worker called the encoder.")
public class EmbeddingBatchStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("Surrogate primary key.")
    Long id;

    @Comment("When this batch was recorded, server-side (not the caller's clock).")
    Instant recordedAt;

    @Comment("Number of texts/paragraphs in this batch.")
    Integer batchSize;

    @Comment("Total character count of every text in the batch - the raw input size the embedding call's cost actually scales with.")
    Long totalChars;

    @Comment("Output vector dimension the embedder produced for this batch, e.g. 1024 for bge-m3.")
    Integer vectorDimension;

    @Comment("Which embedder/model produced this batch, e.g. 'bge-m3' (Ollama) or 'all-MiniLM-L6-v2' (STS).")
    String embedderModel;

    @Comment("Wall-clock time the embedding call itself took, milliseconds - store+flush time is not part of this.")
    Long durationMs;

    // Enforces the "server-side, not the caller's clock" comment on
    // recordedAt above: Spring Data REST's POST otherwise deserializes
    // whatever the client's JSON body happens to contain, including a
    // spoofed/skewed recordedAt.
    @PrePersist
    void onCreate() {
        this.recordedAt = Instant.now();
    }
}
