package ro.editii.scriptorium.embedding;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;

import java.time.Instant;

/**
 * One opus's vectorizing run, as reported by whichever worker walked it
 * (biblioteca-nestjs's VectorizerService.vectorize, today) - the coarser
 * counterpart to {@link EmbeddingBatchStat}: one row per opus finished
 * (whether or not it actually needed any new embedding calls), summing
 * up what happened across all of that opus's batches. Recorded for the
 * same capacity-planning reason - "how long will revectorizing the whole
 * corpus take" needs both the per-batch cost and how batches add up per
 * opus (import/network overhead, paragraph count skew across opera).
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// Note: @Comment texts below must not contain apostrophes - Hibernate
// emits them verbatim, unescaped, in the PostgreSQL "comment on ... is
// '...'" DDL (unlike Derby, which has no COMMENT ON at all), where a
// stray apostrophe terminates the string literal and fails the DDL.
@Comment("Vectorizing run of one opus, reported by whichever worker walked it.")
public class OpusVectorizingStat {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "opus_vectorizing_stat_seq")
    @SequenceGenerator(name = "opus_vectorizing_stat_seq", sequenceName = "opus_vectorizing_stat_seq", allocationSize = 50)
    @Comment("Surrogate primary key.")
    Long id;

    @Comment("When this run was recorded, server-side (not the caller clock).")
    Instant recordedAt;

    @Comment("The TeiDiv id of the opus (biblioteca-nestjs never has a path of its own to report - see BibliotecaClient).")
    Long opusId;

    @Comment("Total paragraphs processed for this opus - includes ones reused/repointed, not just newly embedded.")
    Integer totalParas;

    @Comment("Number of batches that actually called the embedder - 0 means every paragraph was already stored.")
    Integer totalBatches;

    @Comment("Which embedder/model this run used, e.g. bge-m3 (Ollama) or all-MiniLM-L6-v2 (STS).")
    String embedderModel;

    @Comment("Wall-clock time the whole opus took, milliseconds - from the first paragraph fetched to the last vector flushed.")
    Long durationMs;

    @PrePersist
    void onCreate() {
        this.recordedAt = Instant.now();
    }
}
