package ro.editii.scriptorium.collection;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * What every kind of collection has in common - favourites, user-curated
 * collections and saved search results. Mirrored by the reader's
 * {@code Collection} TypeScript interface.
 */
public interface ItemCollection {
    UUID getId();
    String getName();
    CollectionKind getKind();
    Timestamp getCreatedAt();
    List<? extends CollectionEntry> getItems();
}
