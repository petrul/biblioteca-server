package ro.editii.scriptorium.web

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.util.UriComponentsBuilder
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiFile
import ro.editii.scriptorium.tei.TeiRepo

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

/**
 * Random discovery over a tiny corpus - two novels - with native Groovy
 * stubs: the repository answers from an in-memory div table and the TEI
 * repo from a set of present source files. Plain instance, no Spring
 * context: the controller's own logic is all that is under test.
 */
class UtilControllerRandomTest {

    private final Map<Long, TeiDiv> divs = [:]
    private final List<Object[]> bottomRows = []
    private final Set<String> presentSources = [] as Set

    private UtilController controller() {
        new UtilController(
                teiDivRepository: [
                        getBottomDivIdsWithFilename: { -> bottomRows },
                        findById                   : { Long id -> Optional.ofNullable(divs[id]) },
                ] as TeiDivRepository,
                teiRepo: [has: { String filename -> presentSources.contains(filename) }] as TeiRepo)
    }

    /** A novel: one opus div with the given leaf chapters, all under one author. */
    private void novel(String authorId, String opusId, String filename, List<String> chapters, String licenseChapter = null) {
        final file = new TeiFile()
        file.filename = filename
        file.authors = [Author.builder().strId(authorId).build()]
        final opus = new TeiDiv(teiFile: file, urlFragment: opusId, id: (long) (divs.size() + 1))
        divs[opus.id] = opus
        (chapters + (licenseChapter ? [licenseChapter] : [])).each { fragment ->
            final chapter = new TeiDiv(teiFile: file, urlFragment: fragment, parent: opus, id: (long) (divs.size() + 1))
            if (fragment == licenseChapter) chapter.head = 'The Full Project Gutenberg License'
            divs[chapter.id] = chapter
            bottomRows << ([chapter.id, filename] as Object[])
        }
        presentSources << filename
    }

    private String randomPath(UtilController c) {
        final response = c.random(new MockHttpServletRequest(), UriComponentsBuilder.fromUriString('http://test'))
        assertEquals(302, response.statusCode.value())
        return URI.create(response.headers.getLocation().toString()).path
    }

    @Test
    void picksChaptersFromBothNovelsAndNeverALicenseDiv() {
        novel('eminescu', 'geniu_pustiu', 'ro/eminescu-geniu_pustiu.tei.xml', ['cap_i', 'cap_ii'], 'license')
        novel('creanga', 'amintiri', 'ro/creanga-amintiri.tei.xml', ['partea_i', 'partea_ii'])
        final c = controller()

        final seen = (1..200).collect { randomPath(c) } as Set

        assertEquals([
                '/eminescu/geniu_pustiu/cap_i', '/eminescu/geniu_pustiu/cap_ii',
                '/creanga/amintiri/partea_i', '/creanga/amintiri/partea_ii',
        ] as Set, seen)
    }

    @Test
    void leavesOutANovelWhoseSourceIsGone() {
        novel('eminescu', 'geniu_pustiu', 'ro/eminescu-geniu_pustiu.tei.xml', ['cap_i', 'cap_ii'])
        novel('creanga', 'amintiri', 'ro/creanga-amintiri.tei.xml', ['partea_i', 'partea_ii'])
        presentSources.remove('ro/eminescu-geniu_pustiu.tei.xml')
        final c = controller()

        final seen = (1..100).collect { randomPath(c) } as Set

        assertEquals(['/creanga/amintiri/partea_i', '/creanga/amintiri/partea_ii'] as Set, seen)
    }

    @Test
    void skipsANovelRemovedAfterTheCandidateListWasCached() {
        novel('eminescu', 'geniu_pustiu', 'ro/eminescu-geniu_pustiu.tei.xml', ['cap_i'])
        novel('creanga', 'amintiri', 'ro/creanga-amintiri.tei.xml', ['partea_i'])
        final c = controller()
        randomPath(c) // builds the cache with both novels
        presentSources.remove('ro/eminescu-geniu_pustiu.tei.xml')

        final seen = (1..100).collect { randomPath(c) } as Set

        assertEquals(['/creanga/amintiri/partea_i'] as Set, seen)
    }

    @Test
    void failsClearlyWhenNoNovelHasItsSource() {
        novel('eminescu', 'geniu_pustiu', 'ro/eminescu-geniu_pustiu.tei.xml', ['cap_i'])
        novel('creanga', 'amintiri', 'ro/creanga-amintiri.tei.xml', ['partea_i'])
        presentSources.clear()

        assertThrows(IllegalStateException) { randomPath(controller()) }
    }
}
