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
                                .isFavorites(true)
                                .build()));
    }

    @Transactional
    public DivCollection createCollection(AppUser owner, String name) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("collection name must not be blank");
        if (name.length() > DivCollection.NAME_MAX_LENGTH)
            throw new IllegalArgumentException("collection name too long (max " + DivCollection.NAME_MAX_LENGTH + " chars)");
        if (this.divCollectionRepository.existsByOwnerAndName(owner, name))
            throw new IllegalArgumentException("you already have a collection named " + name);

        return this.divCollectionRepository.save(
                DivCollection.builder().owner(owner).name(name).isFavorites(false).build());
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
        if (collection.isFavorites())
            throw new IllegalArgumentException("the favorites collection cannot be deleted");
        this.divCollectionRepository.delete(collection);
    }

    @Transactional
    public DivCollectionItem addDiv(AppUser owner, String collectionName, String divPath) {
        final DivCollection collection = this.getCollection(owner, collectionName);
        final TeiDiv div = resolveDiv(divPath);

        return this.divCollectionItemRepository.save(DivCollectionItem.builder()
                .collection(collection)
                .kind(DivCollectionItem.Kind.DIV)
                .divPath(div.getCompletePath())
                .build());
    }

    @Transactional
    public DivCollectionItem addFragment(AppUser owner, String collectionName, String divPath, String start, String end) {
        final DivCollection collection = this.getCollection(owner, collectionName);
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
                .build());
    }

    @Transactional
    public void removeItem(AppUser owner, String collectionName, Long itemId) {
        final DivCollection collection = this.getCollection(owner, collectionName);
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
