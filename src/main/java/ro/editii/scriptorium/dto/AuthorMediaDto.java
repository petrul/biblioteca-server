package ro.editii.scriptorium.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One author's full set of enrichment-collected media (Wikipedia/Wikimedia
 * image URLs) - the bulk companion to GET /api/authors/{strId}'s own
 * imageUrls field, for reviewing what art enrichment has gathered across
 * the whole corpus at once instead of one author at a time.
 */
@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class AuthorMediaDto {
    String strId;
    String displayName;
    List<String> imageUrls;
}
