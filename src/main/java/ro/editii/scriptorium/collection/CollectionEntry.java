package ro.editii.scriptorium.collection;

import java.sql.Timestamp;
import java.util.UUID;

/** One entry of an {@link ItemCollection}: a work/chapter (by stable path), a quote, or a search hit. */
public interface CollectionEntry {
    UUID getId();
    String getDivPath();
    Timestamp getAddedAt();
}
