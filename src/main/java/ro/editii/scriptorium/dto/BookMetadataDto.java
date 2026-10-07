package ro.editii.scriptorium.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class BookMetadataDto {
    String title;
    String subtitle;
    String author;
    String editor;
    String translator;
    String publisher;
    String pubPlace;
    String date;
    String isbn;
    String series;
    String volume;
    String taglineQuote;
    String genre;
    String editionNotice;
    String language;
    String rawTei;
}
