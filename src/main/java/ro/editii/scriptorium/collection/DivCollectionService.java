package ro.editii.scriptorium.collection;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.editii.scriptorium.dao.DivCollectionItemRepository;
import ro.editii.scriptorium.dao.DivCollectionRepository;
import ro.editii.scriptorium.fragment.FragmentResolutionService;
import ro.editii.scriptorium.model.AppUser;
import ro.editii.scriptorium.model.DivCollectionItem;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.model.DivCollection;
import ro.editii.scriptorium.service.DivService;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DivCollectionService {

    final DivCollectionRepository divCollectionRepository;
    final DivCollectionItemRepository divCollectionItemRepository;
    final DivService divService;
    final FragmentResolutionService fragmentResolutionService;

    @Transactional
    public DivCollection createFavoritesIfMissing(AppUser owner) {
        return this.divCollectionRepository.findByOwnerAndName(owner, DivCollection.FAVORITES_NAME)
                .orElseGet(() -> this.divCollectionRepository.save(
                        DivCollection.builder()
                                .owner(owner)
                                .name(DivCollection.FAVORITES_NAME)
                                .kind(CollectionKind.FAVORITES)
                                .build()));
    }

    @Transactional
    public DivCollection createCollection(AppUser owner, String name) {
        name = validName(name);
        if (this.divCollectionRepository.existsByOwnerAndName(owner, name))
            throw new IllegalArgumentException("you already have a collection named " + name);

        return this.divCollectionRepository.save(
                DivCollection.builder().owner(owner).name(name).kind(CollectionKind.USER).build());
    }

    @Transactional(readOnly = true)
    public List<DivCollection> listCollections(AppUser owner) {
        return this.divCollectionRepository.findByOwner(owner);
    }

    @Transactional(readOnly = true)
    public DivCollection getCollection(AppUser owner, String name) {
        return this.divCollectionRepository.findByOwnerAndName(owner, name)
                .orElseThrow(() -> new IllegalArgumentException("no such collection: " + name));
    }

    @Transactional
    public void deleteCollection(AppUser owner, String name) {
        final DivCollection collection = this.getCollection(owner, name);
        this.delete(collection);
    }

    @Transactional
    public void deleteCollection(AppUser owner, UUID id) {
        this.delete(this.getCollection(owner, id));
    }

    private void delete(DivCollection collection) {
        if (collection.isFavorites())
            throw new IllegalArgumentException("the favorites collection cannot be deleted");
        this.divCollectionRepository.delete(collection);
    }

    @Transactional(readOnly = true)
    public DivCollection getCollection(AppUser owner, UUID id) {
        return this.divCollectionRepository.findByOwnerAndId(owner, id)
                .orElseThrow(() -> new IllegalArgumentException("no such collection: " + id));
    }

    /** Favourites keeps its name; user collections and saved searches can be renamed freely. */
    @Transactional
    public DivCollection renameCollection(AppUser owner, UUID id, String newName) {
        final DivCollection collection = this.getCollection(owner, id);
        if (collection.isFavorites())
            throw new IllegalArgumentException("the favorites collection cannot be renamed");
        final String name = validName(newName);
        if (!name.equals(collection.getName()) && this.divCollectionRepository.existsByOwnerAndName(owner, name))
            throw new IllegalArgumentException("you already have a collection named " + name);
        collection.setName(name);
        return this.divCollectionRepository.save(collection);
    }

    /** Sorts the collection's items as given; items not listed keep their relative order after the listed ones. */
    @Transactional
    public DivCollection reorderItems(AppUser owner, UUID id, List<UUID> itemIds) {
        final DivCollection collection = this.getCollection(owner, id);
        final java.util.Map<UUID, DivCollectionItem> byId = new java.util.HashMap<>();
        collection.getItems().forEach(it -> byId.put(it.getId(), it));
        int position = 0;
        for (UUID itemId : itemIds) {
            final DivCollectionItem item = byId.remove(itemId);
            if (item == null)
                throw new IllegalArgumentException("no such item (" + itemId + ") in collection " + id);
            item.setSortOrder(position++);
        }
        for (DivCollectionItem rest : collection.getItems()) {
            if (byId.containsKey(rest.getId())) rest.setSortOrder(position++);
        }
        collection.getItems().sort(java.util.Comparator.comparingInt(DivCollectionItem::getSortOrder));
        return this.divCollectionRepository.save(collection);
    }

    /** One hit of a search to be saved: the work/chapter's stable path, the matched excerpt, its score. */
    public record SearchHitToSave(String path, String snippet, Float score) {}

    /**
     * Stores a search's results as a SEARCH collection. The name defaults to
     * the query when not supplied; a clash with an existing name gets a
     * numeric suffix, since the same search can legitimately be saved twice.
     */
    @Transactional
    public DivCollection saveSearch(AppUser owner, String name, String query, String paramsJson, List<SearchHitToSave> hits) {
        if (query == null || query.isBlank())
            throw new IllegalArgumentException("search query must not be blank");
        final String base = validName(name == null || name.isBlank() ? query.strip() : name,
                true);
        String unique = base;
        for (int n = 2; this.divCollectionRepository.existsByOwnerAndName(owner, unique); n++) {
            final String suffix = " (" + n + ")";
            unique = base.substring(0, Math.min(base.length(), DivCollection.NAME_MAX_LENGTH - suffix.length())) + suffix;
        }
        final DivCollection collection = DivCollection.builder()
                .owner(owner).name(unique).kind(CollectionKind.SEARCH)
                .searchQuery(query.length() > 500 ? query.substring(0, 500) : query)
                .searchParams(paramsJson)
                .build();
        int position = 0;
        for (SearchHitToSave hit : hits == null ? List.<SearchHitToSave>of() : hits) {
            if (hit.path() == null || hit.path().isBlank()) continue;
            final String snippet = hit.snippet() == null ? null
                    : hit.snippet().length() > 2000 ? hit.snippet().substring(0, 2000) : hit.snippet();
            collection.getItems().add(DivCollectionItem.builder()
                    .collection(collection).kind(DivCollectionItem.Kind.SNIPPET)
                    .divPath(hit.path()).snippet(snippet).score(hit.score())
                    .sortOrder(position++).build());
        }
        return this.divCollectionRepository.save(collection);
    }

    private static String validName(String name) {
        return validName(name, false);
    }

    private static String validName(String name, boolean truncate) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("collection name must not be blank");
        final String stripped = name.strip();
        if (stripped.length() > DivCollection.NAME_MAX_LENGTH) {
            if (!truncate)
                throw new IllegalArgumentException("collection name too long (max " + DivCollection.NAME_MAX_LENGTH + " chars)");
            return stripped.substring(0, DivCollection.NAME_MAX_LENGTH);
        }
        return stripped;
    }

    @Transactional
    public DivCollectionItem addDiv(AppUser owner, String collectionName, String divPath) {
        return this.addDiv(owner, this.getCollection(owner, collectionName).getId(), divPath);
    }

    @Transactional
    public DivCollectionItem addDiv(AppUser owner, UUID collectionId, String divPath) {
        final DivCollection collection = this.getCollection(owner, collectionId);
        final TeiDiv div = resolveDiv(divPath);

        return this.divCollectionItemRepository.save(DivCollectionItem.builder()
                .collection(collection)
                .kind(DivCollectionItem.Kind.DIV)
                .divPath(div.getCompletePath())
                .sortOrder(nextOrder(collection))
                .build());
    }

    private static int nextOrder(DivCollection collection) {
        return collection.getItems().stream().mapToInt(DivCollectionItem::getSortOrder).max().orElse(-1) + 1;
    }

    @Transactional
    public DivCollectionItem addFragment(AppUser owner, String collectionName, String divPath, String start, String end) {
        return this.addFragment(owner, this.getCollection(owner, collectionName).getId(), divPath, start, end);
    }

    @Transactional
    public DivCollectionItem addFragment(AppUser owner, UUID collectionId, String divPath, String start, String end) {
        final DivCollection collection = this.getCollection(owner, collectionId);
        final TeiDiv div = resolveDiv(divPath);

        // Validate the fragment actually resolves before storing a possibly
        // broken one - same checks GET /quote/... itself relies on.
        this.fragmentResolutionService.resolve(div, start, end);

        return this.divCollectionItemRepository.save(DivCollectionItem.builder()
                .collection(collection)
                .kind(DivCollectionItem.Kind.FRAGMENT)
                .divPath(div.getCompletePath())
                .fragmentStart(start)
                .fragmentEnd(end)
                .sortOrder(nextOrder(collection))
                .build());
    }

    @Transactional
    public void removeItem(AppUser owner, String collectionName, UUID itemId) {
        this.removeItem(owner, this.getCollection(owner, collectionName).getId(), itemId);
    }

    @Transactional
    public void removeItem(AppUser owner, UUID collectionId, UUID itemId) {
        final String collectionName = collectionId.toString();
        final DivCollection collection = this.getCollection(owner, collectionId);
        // Removed from the parent's own collection (relying on
        // orphanRemoval), not deleted directly via DivCollectionItemRepository -
        // the latter can get silently "resurrected" by the still-attached
        // parent-side cascade at flush time, since collection.items (still
        // referencing it in this same persistence context) is itself dirty
        // and managed.
        final boolean removed = collection.getItems().removeIf(it -> it.getId().equals(itemId));
        if (!removed)
            throw new IllegalArgumentException("no such item (" + itemId + ") in collection " + collectionName);
        this.divCollectionRepository.save(collection);
    }

    /** Convenience for the "add this page to favorites" UI action. */
    @Transactional
    public DivCollectionItem addDivToFavorites(AppUser owner, String divPath) {
        this.createFavoritesIfMissing(owner);
        return this.addDiv(owner, DivCollection.FAVORITES_NAME, divPath);
    }

    /** Public: DivCollectionRestController's own toDto also needs to resolve items' divPath back to a live TeiDiv. */
    public TeiDiv resolveDiv(String divPath) {
        final TeiElem elem = this.divService.getByPath(divPath);
        if (!(elem instanceof TeiDiv div))
            throw new IllegalArgumentException("path does not resolve to a div/chapter: " + divPath);
        return div;
    }
}
