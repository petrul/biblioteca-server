package ro.editii.scriptorium.rest

import groovy.util.ProxyGenerator
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.UriComponentsBuilder
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiFile
import ro.editii.scriptorium.service.CoverEnrichmentRequests
import ro.editii.scriptorium.tei.TeiRepo

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The Discover shelf's random sample over a small in-memory corpus, with
 * native Groovy stubs for the repository, the TEI source check and the
 * cover requests. Plain instance, no Spring context.
 */
class DivCollectionDiscoverTest {

    private final Map<Long, TeiDiv> opera = [:]
    private final Set<String> presentSources = [] as Set
    private final List<Object> coverRequests = []

    private DivCollectionRestController controller() {
        final divs = [
                getOperaIdsWithFilename: { -> opera.values().collect { [it.id, it.teiFile.filename] as Object[] } },
                findAllById            : { Iterable<Long> ids -> ids.collect { opera[it] }.findAll() },
        ] as TeiDivRepository
        final covers = ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass([
                requestMissing: { Object value -> coverRequests.add(value); null }
        ], CoverEnrichmentRequests, [null] as Object[]) as CoverEnrichmentRequests
        new DivCollectionRestController(null, null, divs, null, null, covers,
                [has: { String filename -> presentSources.contains(filename) }] as TeiRepo)
    }

    private void work(String authorId, String opusId) {
        final file = new TeiFile()
        file.filename = "${authorId}-${opusId}.tei.xml"
        file.authors = [Author.builder().strId(authorId).build()]
        final opus = new TeiDiv(teiFile: file, urlFragment: opusId, id: (long) (opera.size() + 1))
        opera[opus.id] = opus
        presentSources << file.filename
    }

    private List<String> discoverPaths(DivCollectionRestController c, int size) {
        c.discover(size, UriComponentsBuilder.fromUriString('http://test')).collect { it.path }
    }

    @Test
    void returnsTheRequestedNumberOfDistinctWorksAndRequestsTheirCovers() {
        (1..30).each { work("author${it}", "opus${it}") }

        final paths = discoverPaths(controller(), 20)

        assertEquals(20, paths.size())
        assertEquals(20, (paths as Set).size())
        assertTrue(paths.every { it ==~ /author\d+\/opus\d+/ })
        assertEquals(1, coverRequests.size())
    }

    @Test
    void samplesDifferentWorksAcrossCalls() {
        (1..30).each { work("author${it}", "opus${it}") }
        final c = controller()

        final seen = (1..20).collectMany { discoverPaths(c, 5) } as Set

        assertTrue(seen.size() > 5, "expected varied samples, saw ${seen}")
    }

    @Test
    void returnsTheWholeCorpusWhenItIsSmallerThanTheShelf() {
        work('eminescu', 'geniu_pustiu')
        work('creanga', 'amintiri')

        assertEquals(['creanga/amintiri', 'eminescu/geniu_pustiu'] as Set, discoverPaths(controller(), 20) as Set)
    }

    @Test
    void leavesOutWorksWhoseSourceIsGone() {
        work('eminescu', 'geniu_pustiu')
        work('creanga', 'amintiri')
        presentSources.remove('eminescu-geniu_pustiu.tei.xml')

        assertEquals(['creanga/amintiri'], discoverPaths(controller(), 20))
    }

    @Test
    void rejectsAnOutOfRangeSize() {
        work('creanga', 'amintiri')
        final c = controller()

        assertThrows(ResponseStatusException) { c.discover(0, UriComponentsBuilder.fromUriString('http://test')) }
        assertThrows(ResponseStatusException) {
            c.discover(DivCollectionRestController.DISCOVER_MAX_SIZE + 1, UriComponentsBuilder.fromUriString('http://test'))
        }
    }
}
