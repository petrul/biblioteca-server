package ro.editii.scriptorium.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Published once per opus after its source file disappears from the repo
 * (see AdminService.pruneRemovedTeis) - deliberately not a TeiDivDto: by
 * the time this is signalled, the opus's DB row is already gone, so there
 * is no real entity left to describe beyond the path consumers need to
 * purge their own copies (Lucene, Milvus) by.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpusRemovedDto {
    /** TeiDiv.completePath of the removed opus - same value LuceneIndexService/Milvus key content by. */
    String path;
}
