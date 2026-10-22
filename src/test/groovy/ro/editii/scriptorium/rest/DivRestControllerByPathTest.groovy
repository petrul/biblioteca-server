package ro.editii.scriptorium.rest

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.web.util.UriComponentsBuilder
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiElem
import ro.editii.scriptorium.model.TeiFile
import ro.editii.scriptorium.service.DivService

/**
 * GET /api/divs?path= on a paragraph path: getByPath navigates to an
 * in-memory leaf without an id. Merging it would persist it - nextval() in
 * the read-only transaction, which PostgreSQL refuses (the H2 suites never
 * noticed). No Spring, no database: the controller decision alone.
 */
class DivRestControllerByPathTest {

    private final TeiDiv chapter = chapter()

    private static TeiDiv chapter() {
        final file = new TeiFile()
        file.setAuthors([Author.builder().strId('creanga').build()])
        final opus = new TeiDiv()
        opus.setTeiFile(file); opus.setUrlFragment('amintiri'); opus.setId(1L)
        final chapter = new TeiDiv()
        chapter.setTeiFile(file); chapter.setUrlFragment('partea_i'); chapter.setParent(opus); chapter.setId(2L)
        return chapter
    }

    private DivRestController controller(TeiElem found, List<String> merged) {
        final DivService divService = new DivService(null, null, null) {
            @Override TeiElem getByPath(String path) { found }
        }
        final entityManager = [
                contains: { Object it -> false },
                merge: { Object it -> merged << ((TeiElem) it).completePath; it },
        ] as EntityManager
        return new DivRestController(null, divService, null, entityManager, null, null, null)
    }

    @Test
    void aParagraphPathIsAnsweredWithoutPersistingTheParagraph() {
        // as path navigation builds it (TeiElem.elemChildForParent): no id
        final paragraph = TeiElem.builder().name('p').parent(chapter).nth(3).urlFragment('_3').teiFile(chapter.teiFile).build()
        final merged = []

        final dto = controller(paragraph, merged).get_by_path('/creanga/amintiri/partea_i/_3', UriComponentsBuilder.fromUriString('http://server:8080'))

        assert merged.isEmpty()
        assert dto.path == 'creanga/amintiri/partea_i/_3'
        assert dto.url == 'http://server:8080/creanga/amintiri/partea_i/_3'
    }

    @Test
    void aPersistedElementOutsideTheSessionIsStillMerged() {
        final paragraph = TeiElem.builder().name('p').parent(chapter).nth(1).urlFragment('_1').teiFile(chapter.teiFile).id(7L).build()
        final merged = []

        controller(paragraph, merged).get_by_path('/creanga/amintiri/partea_i/_1', UriComponentsBuilder.fromUriString('http://server:8080'))

        assert merged == ['creanga/amintiri/partea_i/_1']
    }
}
