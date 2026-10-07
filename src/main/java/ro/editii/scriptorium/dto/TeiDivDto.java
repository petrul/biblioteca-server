package ro.editii.scriptorium.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.model.TeiDiv;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@NoArgsConstructor @AllArgsConstructor @Builder @EqualsAndHashCode(callSuper = true)
public class TeiDivDto extends TeiElemDto implements Comparable<TeiDivDto> {

    String head;
    int depth;

    // Set (with summary) the first time the biblioteca-nestjs enrichment
    // worker enriches this opus. Summary itself is a @JsonIgnore'd Derby
    // LOB and never crosses the wire, so this attribution column is the
    // wire-visible "enrichment has already run" marker the worker's
    // duplicate guards check (enrichment-listener.service.ts,
    // EnrichmentService.dailySweep) - without it, every replayed event or
    // nightly sweep would re-run Wikipedia/Wikidata for works that are
    // already enriched.
    String summarySourceUrl;

    String coverUrl;
    String description;
    String significantQuote;

    TeiDivDto[] children;
    AuthorDto author;

    boolean leaf; // true if has no children
    boolean opus; // true if root-level work

    /** Media (DivMediaAssociation) attached to this exact div - manual cover-art candidates and/or
     * enrichment-found art. Filled by GET /api/divs/{id} only, same as AuthorDto.imageUrls. */
    java.util.List<String> imageUrls;

    @Builder(builderMethodName = "teiDivDtoBuilder")
    public TeiDivDto(String path, String urlFragment, String head,  String url, int depth,
                     int size, int wordSize,
                     TeiDivDto[] children,
                     TeiDivDto parent) {
        this.path = path;
        this.urlFragment = urlFragment;
        this.head = head;
        this.url = url;
        this.depth = depth;
        this.children = children;
        this.parent = parent;
        this.size = size;
        this.wordSize = wordSize;
    }

    public static TeiDivDto fromTeiDiv(TeiDiv teiDiv, UriComponentsBuilder uriComponentsBuilder) {
        return fromTeiDiv(teiDiv, uriComponentsBuilder.path("/").toUriString());
    }

    public static TeiDivDto fromTeiDiv(TeiDiv teiDiv, String baseUrl) {
        if (teiDiv == null) return null;
        final var _baseUrl = baseUrl != null && baseUrl.endsWith("/") ?
                baseUrl : String.format("%s/", baseUrl);

        final TeiDivDto dto = new TeiDivDto() {{
            id = teiDiv.getId();
            url = _baseUrl + teiDiv.getAuthor().getStrId() + "/" + teiDiv.getUrl();
            head = teiDiv.getHead();
            depth = teiDiv.getDepth();
            size = teiDiv.getSize();
            wordSize = teiDiv.getWordSize();
            urlFragment = teiDiv.getUrlFragment();
            leaf = teiDiv.isLeaf();
            opus = teiDiv.isOpus();
            path = teiDiv.getCompletePath();
            // Every division inherits the detected language of its TEI file;
            // expose it on the DTO so root opuses carry their own work-level
            // language instead of forcing clients to infer it from authors.
            language = teiDiv.getTeiFile() != null && teiDiv.getTeiFile().getLanguage() != null
                    ? teiDiv.getTeiFile().getLanguage().getISO639_1Code()
                    : null;
            author = AuthorDto.from(teiDiv.getAuthor());
            summarySourceUrl = teiDiv.getSummarySourceUrl();
            coverUrl = teiDiv.getCoverUrl();
            if (teiDiv.getOpusMetadata() != null) {
                description = teiDiv.getOpusMetadata().getDescription();
                significantQuote = teiDiv.getOpusMetadata().getSignificantQuote();
                if (coverUrl == null) coverUrl = teiDiv.getOpusMetadata().getCoverUrl();
            }
            xpath = teiDiv.getXpath();
        }};

        return  dto;
    }

    @Override
    public int compareTo(@NotNull TeiDivDto that) {
        return this.getId().compareTo(that.getId());
    }
}
