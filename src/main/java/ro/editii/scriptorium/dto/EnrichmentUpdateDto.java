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
    private String summary;
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
