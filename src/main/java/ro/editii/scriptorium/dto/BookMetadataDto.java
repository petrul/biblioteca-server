package ro.editii.scriptorium.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The cover renderer's BookMetadata shape (src/main/resources/schemas/
 * book-metadata.schema.json, mirrored from biblioteca-covers' own copy -
 * see GET /api/divs/{id}/cover-metadata) - every field a cover theme can
 * draw on. title/author/publisher/date are the schema's required ones and
 * always serialize, even as "" when genuinely unavailable, so the response
 * stays schema-valid rather than omitting a required key outright. The
 * rest only serialize when actually populated: this corpus's TEI sources
 * ship an empty publicationStmt/sourceDesc almost universally, so most of
 * these are "" for most works - that's accurate, not a bug, and a theme
 * should treat an absent key exactly like an empty one.
 */
@Schema(description = "Book metadata available to cover themes - matches schemas/book-metadata.schema.json exactly. " +
        "title/author/publisher/date always come back (possibly \"\"); every other field is omitted entirely " +
        "when this work's TEI source has nothing for it - which is most works, most fields, in this corpus.")
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class BookMetadataDto {
    @Schema(description = "Main title of the book. Required - always present, from this corpus's own reliable DB field.", example = "Poezii")
    String title;

    @Schema(description = "Subtitle or descriptive heading, read live from the opus's own TEI <subtitle> when present. Often absent.", example = "(1864)")
    String subtitle;

    @Schema(description = "Primary author or creator. Required - always present, from this corpus's own reliable DB field.", example = "Costache Negri")
    String author;

    @Schema(description = "Editor or compiler, read live from the TEI header's titleStmt/editor. Almost always absent in this corpus.")
    String editor;

    @Schema(description = "Translator, read live from the TEI header's titleStmt/editor[@role='translator']. Almost always absent in this corpus.")
    String translator;

    @Schema(description = "Publishing house / imprint name. Required - always present; this platform's own fixed value, not read from the TEI source.", example = "Biblioteca")
    String publisher;

    @Schema(description = "City or place of publication, read live from the TEI header's publicationStmt/pubPlace. Almost always absent in this corpus.")
    String pubPlace;

    @Schema(description = "Publication year/date. Required - always present (possibly \"\"). Reads the TEI header's editionStmt/edition/date, which in " +
            "this corpus is the digitization date, not an original publication date - there usually isn't one to read.", example = "2024")
    String date;

    @Schema(description = "ISBN identifier, read live from the TEI header's sourceDesc//idno[@type='ISBN']. Almost always absent - this corpus predates ISBNs or never recorded them.")
    String isbn;

    @Schema(description = "Book series or collection title, read live from the TEI header's seriesStmt/title. Almost always absent in this corpus.")
    String series;

    @Schema(description = "Volume number or designation (e.g. 'I', '2', 'Vol. 1'), read live from the TEI header's seriesStmt/biblScope[@unit='volume']. Almost always absent.")
    String volume;

    @Schema(description = "Epigraph quote or front-cover blurb, read live from the opus's own TEI <epigraph>. Almost always absent in this corpus.")
    String taglineQuote;

    @Schema(description = "Genre / subject classification, read live from the TEI header's profileDesc/textClass/keywords. Almost always absent in this corpus.")
    String genre;

    @Schema(description = "Notice such as 'First Edition', 'Critical Edition', etc., read live from the TEI header's editionStmt/edition text. Almost always absent.")
    String editionNotice;

    @Schema(description = "Language code, from this corpus's own reliable DB field.", example = "ro")
    String language;

    @Schema(description = "Raw TEI-XML source of this opus/div, only included when the request sets ?includeRawTei=true (omitted by default - can be large).")
    String rawTei;
}
