package ro.editii.scriptorium.dto;

import lombok.Data;

import java.util.List;

/** Idempotent, best-effort enrichment written by the NestJS worker. */
@Data
public class EnrichmentUpdateDto {
    private String authorStrId;
    private Long opusId;
    private String bio;
    private String bioSourceUrl;
    /** The work's encyclopedia summary - TeiOpus.summary, never its description. */
    private String summary;
    private String summarySourceUrl;
    /**
     * The work lookup ran to completion, found or not: stamps
     * TeiOpus.workEnrichedAt. Not set when the lookup failed on the way
     * (network), so the work is tried again later.
     */
    private boolean workEnriched;
    /** A manual admin run's explicit switch: replace an existing summary. */
    private boolean overwrite;
    private String significantQuote;
    private String birthDate;
    private String deathDate;
    private String birthPlace;
    private String country;
    private String writingLanguage;
    private List<String> imageUrls;
    private String coverUrl;
    /**
     * Replace an existing coverUrl instead of only filling an empty one -
     * set by deliberate re-renders (biblioteca-nestjs's forced runs and the
     * reader's admin "generate cover"), never by routine enrichment.
     */
    private boolean replaceCover;
}
