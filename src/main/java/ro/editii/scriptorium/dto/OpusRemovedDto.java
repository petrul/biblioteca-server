package ro.editii.scriptorium.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Published once per opus after its source file disappears from the repo
 * (see AdminService.pruneRemovedTeis) - deliberately not a TeiDivDto: by
 * the time this is signalled, the opus's DB row is already gone, so there
 * is no real entity left to describe beyond the path downstream stores
 * keyed their content by. Note the "search data is precious" policy:
 * consumers log the removal but RETAIN their stored content (vectors are
 * manual-only to drop, see the vectorizer's /api/vector-store/remove-opus)
 * - this event informs, it does not mandate a purge.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpusRemovedDto {
    /** TeiDiv.completePath of the removed opus - same value LuceneIndexService/Milvus key content by. */
    String path;
}
