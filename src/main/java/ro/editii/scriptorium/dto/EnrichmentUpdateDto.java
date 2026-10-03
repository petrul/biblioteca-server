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
}
