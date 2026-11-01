package ro.editii.scriptorium.model

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.InMemoryTeiRepo

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * TeiElem caches each file's parsed document by filename. A reimport of a
 * changed file must not keep serving the previous document:
 * forgetParsedFile (called by TeiFileDbService.importTeiFile) drops it.
 */
class TeiElemParsedFileCacheTest {

    static final String FILE = 'en/test/parsed-file-cache.tei.xml'

    static String tei(String speech) {
        """<TEI xmlns="http://www.tei-c.org/ns/1.0"><teiHeader/><text><body>
<div><head>SCENE II.</head><p>${speech}</p></div>
</body></text></TEI>"""
    }

    private static TeiDiv firstDiv(InMemoryTeiRepo repo) {
        final TeiDiv div = new TeiDiv()
        div.setTeiFile(new TeiFile(filename: FILE))
        div.setTeiRepo(repo)
        div.setXpath('/tei:div[1]')
        div.setName('div')
        return div
    }

    @Test
    void 'a forgotten file is parsed again - a reimport serves the new text'() {
        final repo = new InMemoryTeiRepo()
        repo.map[FILE] = tei('Enter Hamlet as a speaker.')
        TeiElem.forgetParsedFile(FILE) // other tests may share the static cache
        assertEquals('Enter Hamlet as a speaker.', firstDiv(repo).node.textContent.find(/Enter[^\n]*/))

        repo.map[FILE] = tei('Enter Hamlet as a direction.')
        assertEquals('Enter Hamlet as a speaker.', firstDiv(repo).node.textContent.find(/Enter[^\n]*/),
                'the parsed document is cached by filename')

        TeiElem.forgetParsedFile(FILE)
        assertEquals('Enter Hamlet as a direction.', firstDiv(repo).node.textContent.find(/Enter[^\n]*/))
    }
}
