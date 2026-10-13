package ro.editii.scriptorium.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.sql.Timestamp;
import java.util.UUID;
import ro.editii.scriptorium.collection.CollectionKind;
import ro.editii.scriptorium.collection.ItemCollection;
import java.util.List;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DivCollectionDto implements ItemCollection {
    UUID id;
    String name;
    CollectionKind kind;
    boolean isFavorites;
    String searchQuery;
    String searchParams; // JSON, SEARCH collections only
    Timestamp createdAt;
    List<DivCollectionItemDto> items;
}
