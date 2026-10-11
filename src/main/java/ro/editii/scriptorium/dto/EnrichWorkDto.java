package ro.editii.scriptorium.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Asks biblioteca-nestjs to enrich one work - today: generate its missing
 * cover. Published on biblioteca_enrichWorkTopic when a work is served to
 * the reader (catalog, author page, carousel, work card) without a cover;
 * see CoverEnrichmentRequests.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnrichWorkDto {
    /** The work's stable id, authorStrId/opusStrId - TeiDiv.completePath of the opus. */
    String strId;
}
