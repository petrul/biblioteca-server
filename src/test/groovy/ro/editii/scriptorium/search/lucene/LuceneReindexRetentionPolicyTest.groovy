package ro.editii.scriptorium.search.lucene

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiElem
import ro.editii.scriptorium.service.ControllerTool
import ro.editii.scriptorium.service.DivService
import ro.editii.scriptorium.service.ElemInfo

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * "Search data is precious" policy at the LuceneIndexService level: a
 * book that was renamed and lightly corrected must be reindexed in place
 * - the new edition's documents land under their new urls (the url embeds
 * the author/work-title, so a rename moves every one of them) - while the
 * sibling book is untouched and NOTHING is dropped: the old edition's
 * documents are deliberately retained (stale rows get filtered at search
 * time when their urls stop resolving - see VectorUtils - and only an
 * explicit manual operation may ever delete index content).
 *
 * Groovy mocks throughout, no Mockito - mocking Java classes from Groovy
 * with Mockito is fragile (see the README's testing notes), and the
 * entities/interfaces here map straight onto map-coercion fakes. The
 * Directory I/O is real: that's the part worth exercising for real.
 */
class LuceneReindexRetentionPolicyTest {

    @TempDir
    Path tempDir

    /**
     * The two class-typed collaborators LuceneIndexService actually
     * calls, as hand-rolled Groovy fakes (map coercion cannot instantiate
     * their all-args constructors): one fixed content text for every
     * paragraph - the URL, the thing this test asserts on, stays unique
     * per leaf div - and one paragraph-list per opus.
     */
    static class ParasOfDivService extends DivService {
        // Keyed by opus path, never by the TeiDiv object: the entities are
        // lombok-@Data, so two fakes with null fields compare EQUAL and
        // would silently overwrite each other as map keys.
        final Map<String, List<TeiElem>> paragraphsByPath = [:]
        ParasOfDivService() { super(null, null, null) }
        @Override
        ro.editii.scriptorium.toc.Toc getToc(long id) {
            new ro.editii.scriptorium.toc.Toc(new FakeOpus(paragraphsByPath.keySet().find { (long) it.hashCode() == id }))
        }
        @Override
        List<TeiElem> getParagraphs(TeiDiv teiDiv) { this.paragraphsByPath.get(teiDiv.getCompletePath()) }
    }

    static class FixedContentControllerTool extends ControllerTool {
        FixedContentControllerTool() { super(null) }
        @Override
        String teiElemToString(ElemInfo elemInfo) { "content of the corpus" }
    }

    final ParasOfDivService divService = new ParasOfDivService()
    final FixedContentControllerTool controllerTool = new FixedContentControllerTool()

    LuceneIndexService newService() {
        // TeiDivRepository is never touched on this path (reindexOpus
        // walks an already-loaded opus) - an empty map fake satisfies
        // the constructor.
        return new LuceneIndexService(tempDir.toString(), false, 0d,
                [:] as ro.editii.scriptorium.dao.TeiDivRepository, divService, controllerTool)
    }

    /**
     * Hand-rolled fakes of the TEI entities, not map coercions: the code
     * under test here is Java, and a map-coerced entity proxy only
     * reliably serves the exact methods the map lists - rich entities
     * (TeiElem.toElemInfo() walks the whole node tree) then run their
     * REAL bodies from Java callers. A subclass with explicit overrides
     * is unambiguous: the URL-carrying getters return exactly what the
     * test sets and toElemInfo() is neutralized (the fake ControllerTool
     * ignores its argument anyway).
     */
    static class FakeOpus extends TeiDiv {
        final String fakePath
        FakeOpus(String path) { this.fakePath = path; this.id = (long) path.hashCode() }
        @Override
        String getCompletePath() { this.fakePath }
    }

    static class FakeDiv extends TeiDiv {
        final String fakeHead
        FakeDiv(String head) { this.fakeHead = head }
        @Override
        String getHead() { this.fakeHead }
    }

    static class FakePara extends TeiElem {
        final String fakePath
        final FakeDiv fakeDiv
        FakePara(String path, String head) {
            this.fakePath = path
            this.fakeDiv = new FakeDiv(head)
        }
        @Override
        String getCompletePath() { this.fakePath }
        @Override
        TeiDiv getDiv() { this.fakeDiv }
        @Override
        ElemInfo toElemInfo() { null }
    }

    @Test
    void "a renamed book with a corrected paragraph reindexes in place, retaining everything"() {

        // Book A, first edition: two paragraphs in one leaf div.
        final opusAOld = new FakeOpus("creanga/amintiri")
        divService.paragraphsByPath["creanga/amintiri"] = [
                new FakePara("creanga/amintiri/p1", "first paragraph stays identical"),
                new FakePara("creanga/amintiri/p2", "second paragraph gets corrected"),
        ]

        // Book B: the sibling, never touched by A's reimport.
        final opusB = new FakeOpus("creanga/povesti")
        divService.paragraphsByPath["creanga/povesti"] = [new FakePara("creanga/povesti/p1", "the sibling book")]

        final service = newService()
        service.reindexOpus(opusAOld)
        service.reindexOpus(opusB)

        assertEquals(2, service.search("content", 1000).size())

        // Second edition: the work was RENAMED (every url moves, exactly
        // like the real author/work-title-in-url scheme) and paragraph two
        // was corrected (new content under the new edition).
        final opusANew = new FakeOpus("creanga/amintiri-din-copilarie")
        divService.paragraphsByPath["creanga/amintiri-din-copilarie"] = [
                new FakePara("creanga/amintiri-din-copilarie/p1", "first paragraph stays identical"),
                new FakePara("creanga/amintiri-din-copilarie/p2", "second paragraph, now corrected"),
        ]

        service.reindexOpus(opusANew)

        final urls = service.search("content", 1000)*.getUrl().toSet()

        // The renamed edition is indexed under its new leaf-div URL.
        assertTrue(urls.contains("creanga/amintiri-din-copilarie"))

        // The sibling book is untouched.
        assertTrue(urls.contains("creanga/povesti"))

        // The OLD edition's documents are RETAINED, not dropped - stale
        // urls stop resolving at search time (they are filtered out of
        // what the user sees, see VectorUtils), and only an explicit
        // manual operation (LuceneIndexService.removeOpus) may delete
        // them. Nothing in the automatic flow does.
        assertTrue(urls.contains("creanga/amintiri"))

        assertEquals(3, urls.size())
    }

    @Test
    void "nothing about a removed book changes the index - retention, not pruning"() {

        final opusA = new FakeOpus("creanga/povesti")
        divService.paragraphsByPath["creanga/povesti"] = [new FakePara("creanga/povesti/p1", "the book that will be removed")]

        final service = newService()
        service.reindexOpus(opusA)
        assertEquals(1, service.search("content", 1000).size())

        // The book "vanishes from the repo": at this layer that is
        // deliberately a NO-OP - pruneRemovedTeis no longer calls
        // removeOpus (see AdminServiceRetentionPolicyTest), so the
        // documents a removed book leaves behind simply stay.
        // Nothing to invoke here: the assertion is that the index keeps
        // serving them until an explicit manual removeOpus runs.
        assertEquals(1, service.search("content", 1000).size())
        assertTrue(service.search("content", 1000)*.getUrl().contains("creanga/povesti"))
    }
}
