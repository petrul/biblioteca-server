package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import ro.editii.scriptorium.collection.DivCollectionService;
import ro.editii.scriptorium.dao.AppUserRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.dto.DivCollectionItemDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.dto.AuthorDto;
import ro.editii.scriptorium.dto.DivCollectionDto;
import ro.editii.scriptorium.fragment.FragmentResolutionService;
import ro.editii.scriptorium.model.AppUser;
import ro.editii.scriptorium.model.DivCollectionItem;
import ro.editii.scriptorium.model.Languages;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiFile;
import ro.editii.scriptorium.model.DivCollection;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Two families of collection, sharing the same "unit is a TeiDiv or a
 * Fragment" concept but exposed very differently:
 *
 * - /mine/* : real, persisted, per-user collections (DivCollection/
 *   DivCollectionItem) - always requires being logged in as that user (see
 *   SecurityConfig's /api/collections/mine/** rule). "favorites" is just
 *   one of these, auto-created at registration (see
 *   AppUserRegistrationService), nothing special about its plumbing here.
 * - /system/* : computed, public, read-only groupings (by language, by
 *   author, by repo) - never persisted, since they're fully derivable from
 *   existing TeiFile/TeiDiv data.
 */
@RestController
@RequestMapping("/api/collections")
@CrossOrigin
@RequiredArgsConstructor
@Log4j2
public class DivCollectionRestController {

    final DivCollectionService divCollectionService;
    final AppUserRepository appUserRepository;
    final TeiDivRepository teiDivRepository;
    final TeiFileRepository teiFileRepository;
    final FragmentResolutionService fragmentResolutionService;

    private AppUser currentUser(Authentication authentication) {
        return this.appUserRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated as '" + authentication.getName() + "' but no matching AppUser row"));
    }

    private DivCollectionDto toDto(DivCollection collection) {
        final List<DivCollectionItemDto> items = collection.getItems().stream()
                .map(this::toDto)
                .toList();
        return DivCollectionDto.builder()
                .id(collection.getId())
                .name(collection.getName())
                .isFavorites(collection.isFavorites())
                .createdAt(collection.getCreatedAt())
                .items(items)
                .build();
    }

    private DivCollectionItemDto toDto(DivCollectionItem item) {
        // The stored divPath may have gone stale (the div was renamed or
        // removed since this item was added) - a resolution failure here
        // is not fatal, same tolerance as the fragment-resolution catch
        // below: the item is still listed, just without a live head/text.
        TeiDiv div = null;
        try {
            div = this.divCollectionService.resolveDiv(item.getDivPath());
        } catch (Exception e) {
            log.warn("Could not resolve item {}'s divPath {}: {}", item.getId(), item.getDivPath(), e.getMessage());
        }

        List<String> fragmentText = null;
        if (div != null && item.getKind() == DivCollectionItem.Kind.FRAGMENT) {
            try {
                fragmentText = this.fragmentResolutionService
                        .resolve(div, item.getFragmentStart(), item.getFragmentEnd())
                        .getParagraphs();
            } catch (Exception e) {
                // The underlying div's content may have changed (reimport)
                // since this item was added - don't fail the whole listing
                // over one now-broken fragment.
                log.warn("Could not re-resolve stored fragment (item {}, div {}, {}..{}): {}",
                        item.getId(), item.getDivPath(),
                        item.getFragmentStart(), item.getFragmentEnd(), e.getMessage());
            }
        }
        return DivCollectionItemDto.from(item, div != null ? div.getHead() : null, fragmentText);
    }

    // ---- /mine ----

    @Value
    public static class CreateCollectionRequest {
        String name;
    }

    @Value
    public static class AddItemRequest {
        String type; // "div" or "fragment"
        String path; // div path
        String start; // fragment only
        String end;   // fragment only
    }

    @GetMapping("/mine")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public List<DivCollectionDto> mine(Authentication authentication) {
        return this.divCollectionService.listCollections(currentUser(authentication)).stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/mine")
    public DivCollectionDto create(Authentication authentication, @RequestBody CreateCollectionRequest request) {
        try {
            return toDto(this.divCollectionService.createCollection(currentUser(authentication), request.getName()));
        } catch (IllegalArgumentException e) {
            RestUtil.throw400(e.getMessage());
            return null;
        }
    }

    @GetMapping("/mine/{name}")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public DivCollectionDto get(Authentication authentication, @PathVariable String name) {
        try {
            return toDto(this.divCollectionService.getCollection(currentUser(authentication), name));
        } catch (IllegalArgumentException e) {
            RestUtil.throw404(e.getMessage());
            return null;
        }
    }

    @DeleteMapping("/mine/{name}")
    public void delete(Authentication authentication, @PathVariable String name) {
        try {
            this.divCollectionService.deleteCollection(currentUser(authentication), name);
        } catch (IllegalArgumentException e) {
            RestUtil.throw400(e.getMessage());
        }
    }

    @PostMapping("/mine/{name}/items")
    public DivCollectionItemDto addItem(Authentication authentication, @PathVariable String name,
                                      @RequestBody AddItemRequest request) {
        try {
            final AppUser owner = currentUser(authentication);
            final DivCollectionItem item = "fragment".equalsIgnoreCase(request.getType())
                    ? this.divCollectionService.addFragment(owner, name, request.getPath(), request.getStart(), request.getEnd())
                    : this.divCollectionService.addDiv(owner, name, request.getPath());
            return toDto(item);
        } catch (IllegalArgumentException e) {
            RestUtil.throw400(e.getMessage());
            return null;
        }
    }

    @DeleteMapping("/mine/{name}/items/{itemId}")
    public void removeItem(Authentication authentication, @PathVariable String name, @PathVariable Long itemId) {
        try {
            this.divCollectionService.removeItem(currentUser(authentication), name, itemId);
        } catch (IllegalArgumentException e) {
            RestUtil.throw400(e.getMessage());
        }
    }

    // ---- /system ----

    private static final int SYSTEM_COLLECTION_LIMIT = 200;

    /**
     * Stable authorId/opusId references. A missing edition is omitted until
     * it is imported; no title or database-ID matching is allowed here.
     */
    private static final List<String> FEATURED_WORK_PATHS = List.of(
            "petru/pytho",
            "mitru/povesti_despre_pacala_si_tandala",
            "dulfu/ispravile_lui_pacala",
            "creanga/amintiri_din_copilarie",
            "goethe/faust",
            "alighieri/la_divina_commedia",
            "plato/apology",
            "montaigne/essais",
            "lamartine/meditations_poetiques",
            "shakespeare/the_tragedy"
    );

    @GetMapping("/system/by-language/{lang}")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public List<TeiDivDto> byLanguage(@PathVariable String lang, UriComponentsBuilder ucb) {
        final Languages language = Languages.from(lang);
        if (language == null) {
            RestUtil.throw400("unknown language code: " + lang);
            return null;
        }
        return this.teiDivRepository.findOperaByLang(language, PageRequest.of(0, SYSTEM_COLLECTION_LIMIT))
                .stream()
                .map(it -> TeiDivDto.fromTeiDiv(it, ucb))
                .toList();
    }

    @GetMapping("/system/by-author/{authorStrId}")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public List<TeiDivDto> byAuthor(@PathVariable String authorStrId, UriComponentsBuilder ucb) {
        return this.teiDivRepository.findOperaForAuthorStrId(authorStrId).stream()
                .map(it -> TeiDivDto.fromTeiDiv(it, ucb))
                .toList();
    }

    @GetMapping("/system/by-repo/{repoName}")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public List<TeiDivDto> byRepo(@PathVariable String repoName, UriComponentsBuilder ucb) {
        final List<TeiFile> teiFiles = this.teiFileRepository.findByRepoName(repoName);
        return teiFiles.stream()
                .flatMap(tf -> this.teiDivRepository.getOperaForTeiFileId(tf.getId()).stream())
                .map(it -> TeiDivDto.fromTeiDiv(it, ucb))
                .toList();
    }

    @GetMapping("/system/repos")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public List<String> repoNames() {
        return this.teiFileRepository.findDistinctRepoNames();
    }

    /**
     * Stable, read-only system collection used by the reader's featured
     * carousel.  It is backed by the curated allow-list above, not by import
     * order, so unrelated newly imported works cannot appear here by chance.
     */
    @GetMapping("/system/featured")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public List<TeiDivDto> featured(UriComponentsBuilder ucb) {
        return FEATURED_WORK_PATHS.stream()
                .map(DivCollectionRestController::stablePathParts)
                .flatMap(parts -> this.teiDivRepository.findOperaByStablePath(parts.authorId(), parts.opusId()).stream())
                .map(it -> featuredDto(it, ucb))
                .toList();
    }

    private record StablePath(String authorId, String opusId) {}

    private static StablePath stablePathParts(String path) {
        final int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            throw new IllegalStateException("invalid featured stable path: " + path);
        }
        return new StablePath(path.substring(0, slash), path.substring(slash + 1));
    }

    private static TeiDivDto featuredDto(TeiDiv div, UriComponentsBuilder ucb) {
        final String path = div.getTeiFile().getAuthors().stream()
                .filter(a -> a.getStrId() != null)
                .map(a -> a.getStrId() + "/" + div.getUrlFragment())
                .filter(FEATURED_WORK_PATHS::contains)
                .findFirst()
                .orElseThrow();
        final var dto = new TeiDivDto();
        dto.setId(div.getId());
        dto.setPath(path);
        dto.setUrl(ucb.path("/").toUriString() + path);
        dto.setUrlFragment(div.getUrlFragment());
        dto.setHead(div.getHead());
        dto.setDepth(div.getDepth());
        dto.setSize(div.getSize());
        dto.setWordSize(div.getWordSize());
        dto.setXpath(div.getXpath());
        dto.setLeaf(div.isLeaf());
        dto.setOpus(div.isOpus());
        dto.setAuthor(AuthorDto.from(div.getTeiFile().getAuthors().stream()
                .filter(a -> path.startsWith(a.getStrId() + "/"))
                .findFirst().orElseThrow()));
        return dto;
    }
}
