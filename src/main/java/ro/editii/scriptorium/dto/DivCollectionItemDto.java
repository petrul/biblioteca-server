package ro.editii.scriptorium.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import ro.editii.scriptorium.model.DivCollectionItem;

import java.sql.Timestamp;
import java.util.List;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DivCollectionItemDto {
    Long id;
    String kind; // "DIV" or "FRAGMENT"
    String divPath;
    String divHead;
    String fragmentStart;
    String fragmentEnd;
    List<String> fragmentText; // resolved quote paragraphs - FRAGMENT items only
    Timestamp addedAt;

    // divHead isn't stored on the entity (divPath is - see its own doc
    // comment), so the caller resolves it fresh (null if the path has
    // gone stale since this item was added - same tolerance
    // DivCollectionRestController.toDto already applies to fragmentText).
    public static DivCollectionItemDto from(DivCollectionItem item, String divHead, List<String> resolvedFragmentText) {
        return DivCollectionItemDto.builder()
                .id(item.getId())
                .kind(item.getKind().name())
                .divPath(item.getDivPath())
                .divHead(divHead)
                .fragmentStart(item.getFragmentStart())
                .fragmentEnd(item.getFragmentEnd())
                .fragmentText(resolvedFragmentText)
                .addedAt(item.getAddedAt())
                .build();
    }
}
