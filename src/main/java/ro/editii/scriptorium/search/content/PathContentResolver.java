package ro.editii.scriptorium.search.content;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dao.RelocationRepository;
import ro.editii.scriptorium.model.Relocation;
import ro.editii.scriptorium.service.ControllerTool;
import ro.editii.scriptorium.service.DivService;

import java.net.URI;
import java.util.Optional;

/**
 * Resolves a search hit's stored URL to its plain text in-process - the
 * same text DivController serves for {@code <path>.txt}, without the HTTP
 * round trip back into this server that UrlContentResolver used to make.
 *
 * That round trip deadlocked: the search request held its pooled DB
 * connection (open-in-view) while each nested {@code .txt} request needed
 * one of its own, so ~10 concurrent vector searches (the reader's
 * availability probes) exhausted the pool and every request waited on
 * the others forever - the plain RestTemplate had no read timeout. Here
 * the text comes from the caller's own connection.
 *
 * Only the URL's path matters: the vector stores hold URLs written by
 * whichever server the vectorizer talked to (http://server:8080/... from
 * the Docker stack, http://localhost:8080/... bare metal), and none of
 * those hosts needs to be reachable from here. Relocated (renamed) paths
 * are followed the way the {@code .txt} endpoint's redirect was.
 *
 * The search runs inside one read-only transaction (SearchRestController),
 * so a stale hit must not throw across a transactional proxy - that would
 * fail the whole search at commit. DivService.findElemInfoByPath reports a
 * miss as empty instead.
 */
@Log4j2
@RequiredArgsConstructor
public class PathContentResolver implements ContentResolver {

    // A relocation can point at a path that was itself relocated later;
    // bounded so a cycle cannot spin.
    private static final int MAX_RELOCATION_HOPS = 5;

    final DivService divService;
    final ControllerTool controllerTool;
    final RelocationRepository relocationRepository;

    @Override
    public String resolve(String id) {
        assert id != null;
        try {
            return this.divService.findElemInfoByPath(followRelocations(pathOf(id)))
                    .map(this.controllerTool::teiElemToString)
                    .orElse(null);
        } catch (Exception e) {
            // Not found (removed book, deleted paragraph) or unreadable: null,
            // so the hit is filtered out rather than shown as a dead link
            // (see ContentResolver's contract).
            log.debug("cannot resolve content for {}: {}", id, e.toString());
            return null;
        }
    }

    /** "/author/opus/div" from a full URL or a bare path, without any extension. */
    static String pathOf(String id) {
        final String path = id.contains("://") ? URI.create(id).getRawPath() : id;
        final String decoded = Util.basename(Util.urlDecode(path));
        return decoded.startsWith("/") ? decoded : "/" + decoded;
    }

    private String followRelocations(String path) {
        String current = path;
        for (int hop = 0; hop < MAX_RELOCATION_HOPS; hop++) {
            final Optional<Relocation> relocation = findRelocation(current);
            if (relocation.isEmpty())
                return current;
            final String next = relocation.get().getNewPath();
            current = next.startsWith("/") ? next : "/" + next;
        }
        return current;
    }

    // Relocations are keyed with or without the leading slash.
    private Optional<Relocation> findRelocation(String path) {
        final Optional<Relocation> withSlash = this.relocationRepository.findById(path);
        return withSlash.isPresent() ? withSlash : this.relocationRepository.findById(path.substring(1));
    }
}
