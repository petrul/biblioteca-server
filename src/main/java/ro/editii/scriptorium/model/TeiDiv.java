package ro.editii.scriptorium.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.dto.TeiElemDto;

/**
 * a <div> inside a TEI (may be a fragment, a work, or something)
 */
@Entity
@DiscriminatorValue("div")
@Log4j2
@Data @NoArgsConstructor
@EqualsAndHashCode(callSuper = true) @ToString(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TeiDiv extends TeiElem {

    final public static int MAX_HEAD_SIZE = 3000;

    @Size(max=MAX_HEAD_SIZE) @Column(length = MAX_HEAD_SIZE)
    @EqualsAndHashCode.Include
    String head;

    // Auto-generated (AiEnrichmentService, async after import) - only
    // ever meaningful on an opus (root) div, same reasoning as
    // Author.bio: a trusted source (Wikipedia's summary API) when this
    // work has a page there, else the search engine's own snippets.
    // Null until enrichment has run once; a later reimport never
    // overwrites an existing value.
    @Lob @ToString.Exclude
    String summary;

    @Column(length = 500)
    String summarySourceUrl;

    @Override
    public TeiElemDto toDto(UriComponentsBuilder uriComponentsBuilder) {
        return TeiDivDto.fromTeiDiv(this, uriComponentsBuilder);
    }

    @Override
    public String getVisualLabel() {
        return this.head;
    }

    public boolean isLicense() {
        if (this.head == null)
            return false;
        final var headlower = this.head.toLowerCase().trim()
                .replaceAll("\\s+", " ");
        return
                headlower.contains("the full project gutenberg license")
                || headlower.contains("à propos de cette édition électronique")
                || headlower.contains("ende dieses projekt gutenberg etextes")
                || headlower.contains("questo e-book è stato realizzato")
                ;
    }
}

