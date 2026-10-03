package ro.editii.scriptorium.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import ro.editii.scriptorium.model.Author;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class AuthorDto  {

    /**
     * e.g. balcescu, should be all-lowercase, no-accent version of lastName
     */
    String strId;

    /**
     * Alecsandri
     */
    @EqualsAndHashCode.Include
    String lastName;

    /**
     * e.g. Vasile
     */
    @EqualsAndHashCode.Include
    String firstName;

    /**
     * e.g. "Alecsandri,Vasile". the file name as in the TEI. maybe useful for identifying authors
     * that have already been inserted
     */
//    String originalNameInTeiFile;

    // prefered display name; if null, computed from firstName and lastName by method getVisualName()
    String displayName;
    String description; // remove
    // Present (possibly null) so the biblioteca-nestjs enrichment worker
    // can tell already-enriched authors from enrichment candidates
    // without a second query - see EnrichmentRestController.
    String bio;
    String bioSourceUrl;
    String birthDate;
    String deathDate;
    String birthPlace;
    String country;
    String writingLanguage;
    /** Number of root works associated with this author, keyed by strId. */
    Long worksCount;
    OpusDto[] opera;
    String image_href;

    public static AuthorDto from(Author author) {
        if (author == null) return null;
        return AuthorDto.builder()
                .strId(author.getStrId())
                .lastName(author.getLastName())
                .firstName(author.getFirstName())
//                .originalNameInTeiFile(author.getOriginalNameInTeiFile())
                .displayName(author.getVisualName())
                .bio(author.getBio())
                .bioSourceUrl(author.getBioSourceUrl())
                .birthDate(author.getBirthDate())
                .deathDate(author.getDeathDate())
                .birthPlace(author.getBirthPlace())
                .country(author.getCountry())
                .writingLanguage(author.getWritingLanguage() == null ? null : author.getWritingLanguage().name())
                .worksCount(null)
//                .description(author.getDescription())
                .build();
    }
}
