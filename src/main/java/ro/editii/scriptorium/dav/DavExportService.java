package ro.editii.scriptorium.dav;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.model.Languages;
import ro.editii.scriptorium.model.TeiDiv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DavExportService {
    private final TeiDivRepository teiDivRepository;

    /** One opus of the slim listing with its (possibly several) author refs. */
    private record OpusEntry(
            long divId,
            String filename,
            String urlFragment,
            String head,
            Languages language,
            List<AuthorRef> authors,
            long childCount) {
    }

    private record AuthorRef(String strId, String visualName) {
    }

    public Optional<DavResource> resolve(List<String> path, DavExportOptions options) {
        final List<OpusEntry> opera = eligibleOpera(options);
        if (path.isEmpty())
            return Optional.of(new DavResource(List.of(), "Textbase", true, null));

        final Languages language = Languages.from(path.get(0));
        if (language == null || opera.stream().noneMatch(it -> it.language() == language))
            return Optional.empty();
        if (path.size() == 1)
            return Optional.of(new DavResource(path, language.getISO639_1Code(), true, null));

        final String authorId = path.get(1);
        final List<OpusEntry> authorOpera = opera.stream()
                .filter(it -> it.language() == language)
                .filter(it -> hasAuthor(it, authorId))
                .toList();
        if (authorOpera.isEmpty())
            return Optional.empty();
        if (path.size() == 2)
            return Optional.of(new DavResource(path, authorDisplayName(authorOpera.getFirst(), authorId), true, null));

        // The opus level: matched against the slim listing, then loaded
        // whole - the one full entity of the whole request.
        final String opusName = path.get(2);
        final OpusEntry entry = authorOpera.stream()
                .filter(it -> opusEntryName(it, options).equals(opusName))
                .findFirst().orElse(null);
        if (entry == null)
            return Optional.empty();

        final TeiDiv opus = teiDivRepository
                .findOperaByStablePath(authorId, entry.urlFragment())
                .orElse(null);
        if (opus == null)
            return Optional.empty();

        TeiDiv current = opus;
        int depth = 1;
        for (int pathIndex = 3; pathIndex < path.size(); pathIndex++) {
            final int childDepth = pathIndex - 1;
            final String requestedName = path.get(pathIndex);
            current = divChildren(current).stream()
                    .filter(it -> entryName(it, childDepth, options).equals(requestedName))
                    .findFirst().orElse(null);
            if (current == null)
                return Optional.empty();
            depth = childDepth;
            if (isFile(current, childDepth, options) && pathIndex != path.size() - 1)
                return Optional.empty();
        }

        return Optional.of(new DavResource(
                path,
                current.getVisualLabel(),
                !isFile(current, depth, options),
                current));
    }

    public List<DavResource> children(DavResource parent, DavExportOptions options) {
        if (!parent.collection())
            return List.of();

        final List<OpusEntry> opera = eligibleOpera(options);
        final List<String> path = parent.path();
        if (path.isEmpty()) {
            final Map<String, Languages> languages = new LinkedHashMap<>();
            opera.stream().map(OpusEntry::language).filter(it -> it != null)
                    .sorted(Comparator.comparing(Languages::getISO639_1Code))
                    .forEach(it -> languages.putIfAbsent(it.getISO639_1Code(), it));
            return languages.keySet().stream()
                    .map(it -> new DavResource(List.of(it), it, true, null)).toList();
        }

        final Languages language = Languages.from(path.get(0));
        if (path.size() == 1) {
            final Map<String, String> authors = new LinkedHashMap<>();
            opera.stream().filter(it -> it.language() == language)
                    .flatMap(it -> it.authors().stream())
                    .sorted(Comparator.comparing(AuthorRef::strId))
                    .forEach(it -> authors.putIfAbsent(it.strId(), it.visualName()));
            return authors.entrySet().stream()
                    .map(it -> new DavResource(append(path, it.getKey()), it.getValue(), true, null)).toList();
        }

        if (path.size() == 2) {
            final String authorId = path.get(1);
            return opera.stream()
                    .filter(it -> it.language() == language)
                    .filter(it -> hasAuthor(it, authorId))
                    .map(it -> new DavResource(
                            append(path, opusEntryName(it, options)),
                            it.head(),
                            !opusIsFile(it, options),
                            // Slim-listing children: displayname and
                            // resourcetype only; the timestamps of a
                            // directory entry are not worth an entity each.
                            null))
                    .toList();
        }

        final int childDepth = path.size() - 1;
        return divChildren(parent.div()).stream()
                .sorted()
                .map(it -> new DavResource(
                        append(path, entryName(it, childDepth, options)),
                        it.getVisualLabel(),
                        !isFile(it, childDepth, options),
                        it))
                .toList();
    }

    /**
     * The slim opus listing (see findAllOperaRowsForDav): rows grouped by
     * div - one row per (opus, author) - filtered by the mount's language
     * and author, ordered like the entities were (TeiFile filename, see
     * TeiElem.compareTo).
     */
    private List<OpusEntry> eligibleOpera(DavExportOptions options) {
        final Map<Long, List<DavOperaRow>> byDiv = new LinkedHashMap<>();
        for (final DavOperaRow row : teiDivRepository.findAllOperaRowsForDav()) {
            if (options.language() != null && row.language() != options.language())
                continue;
            if (options.author() != null && !options.author().equals(row.authorStrId()))
                continue;
            byDiv.computeIfAbsent(row.divId(), ignored -> new ArrayList<>()).add(row);
        }

        return byDiv.values().stream()
                .map(rows -> {
                    final DavOperaRow first = rows.getFirst();
                    final List<AuthorRef> authors = rows.stream()
                            .filter(row -> row.authorStrId() != null)
                            .map(row -> new AuthorRef(row.authorStrId(), row.authorVisualName()))
                            .toList();
                    return new OpusEntry(first.divId(), first.filename(), first.urlFragment(),
                            first.head(), first.language(), authors, first.childCount());
                })
                .sorted(Comparator.comparing(OpusEntry::filename).thenComparing(OpusEntry::urlFragment))
                .toList();
    }

    private boolean hasAuthor(OpusEntry entry, String authorId) {
        return entry.authors().stream().anyMatch(it -> it.strId().equals(authorId));
    }

    private String authorDisplayName(OpusEntry entry, String authorId) {
        return entry.authors().stream()
                .filter(it -> it.strId().equals(authorId))
                .map(AuthorRef::visualName)
                .findFirst().orElse(authorId);
    }

    private List<TeiDiv> divChildren(TeiDiv div) {
        if (div.getDbChildren() == null)
            return List.of();
        return div.getDbChildrenAsDivs();
    }

    private boolean isFile(TeiDiv div, int depth, DavExportOptions options) {
        return depth >= options.fragmentationDepth() || divChildren(div).isEmpty();
    }

    private String entryName(TeiDiv div, int depth, DavExportOptions options) {
        return div.getUrlFragment() + (isFile(div, depth, options) ? "." + options.format().extension() : "");
    }

    /** Whether the opus itself is a file at depth 1 (fragmentation 1, or a single-div work). */
    private boolean opusIsFile(OpusEntry entry, DavExportOptions options) {
        return 1 >= options.fragmentationDepth() || entry.childCount() == 0;
    }

    private String opusEntryName(OpusEntry entry, DavExportOptions options) {
        return entry.urlFragment() + (opusIsFile(entry, options) ? "." + options.format().extension() : "");
    }

    private List<String> append(List<String> path, String value) {
        final List<String> result = new ArrayList<>(path);
        result.add(value);
        return List.copyOf(result);
    }
}
