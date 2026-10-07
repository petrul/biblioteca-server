package ro.editii.scriptorium.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ro.editii.scriptorium.dto.OpusRemovedDto
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiElem
import ro.editii.scriptorium.model.TeiFile
import ro.editii.scriptorium.search.lucene.LuceneIndexService

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * "Search data is precious" policy at the AdminService seam: when a book
 * is removed from the repo, pruneRemovedTeis prunes the DB rows and emits
 * the opusRemoved event (the vectorizer logs it and retains its vectors -
 * removal is manual-only there), but it must NOT touch the Lucene index.
 *
 * Groovy mocks throughout, no Mockito - mocking Java classes from Groovy
 * with Mockito is fragile (see the README's testing notes). Repos and
 * entities are map-coerced fakes (only the exact methods the code under
 * test calls); the two class-typed collaborators are hand-rolled Groovy
 * fake subclasses; and LuceneIndexService is REAL, over a temp directory
 * with the removed book's opus actually indexed - so the retention
 * assertion is behavioral, not interaction-based: after the prune, the
 * documents must still be searchable.
 */
class AdminServiceRetentionPolicyTest {

    @TempDir
    Path tempDir

    /** Records deleteTeiFile calls instead of touching the DB. */
    static class RecordingTeiFileDbService extends TeiFileDbService {
        final List<String> deleted = []
        RecordingTeiFileDbService() { super(null, null, null, null, null, null, null, null, null, null, null, null, null) }
        @Override
        void deleteTeiFile(String teiFilename) { this.deleted << teiFilename }
    }

    /** Records the emitted opusRemoved events. */
    final List<OpusRemovedDto> removalEvents = []
    final ro.editii.scriptorium.kafka.TextbaseEventsPublisher eventsPublisher =
            [signalOpusRemoved: { OpusRemovedDto event -> this.removalEvents << event }] \
                    as ro.editii.scriptorium.kafka.TextbaseEventsPublisher

    /** The paragraph source LuceneIndexService reads - same reasoning as
     *  LuceneReindexRetentionPolicyTest's fakes (see that file). */
    static class ParasOfDivService extends DivService {
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
    void "pruning a removed book deletes DB rows and signals the event, but the Lucene index keeps serving it"() {

        // One TeiFile known to the DB, gone from the repo's disk listing.
        final teiFile = [getFilename: { "creanga/povesti.xml" }, getId: { 7L }] as TeiFile
        final opus = new FakeOpus("creanga/povesti")

        final teiRepo = [list: { List.of() }] as ro.editii.scriptorium.tei.TeiRepo
        final teiFileRepository = [findAll: { List.of(teiFile) }] as ro.editii.scriptorium.dao.TeiFileRepository
        final teiDivRepository = [getOperaPathsForTeiFileId: { Long id -> List.of("creanga/povesti") }] \
                as ro.editii.scriptorium.dao.TeiDivRepository
        final teiFileDbService = new RecordingTeiFileDbService()

        // A REAL Lucene index with the (about-to-be-removed) book's opus
        // actually indexed - the retention assertion below checks it
        // still serves those documents after the prune.
        final divService = new ParasOfDivService()
        divService.paragraphsByPath["creanga/povesti"] = [new FakePara("creanga/povesti/p1", "the book that will be removed")]
        final luceneIndexService = new LuceneIndexService(tempDir.toString(), false, 0d,
                teiDivRepository, divService, new FixedContentControllerTool())
        luceneIndexService.reindexOpus(opus)
        assertEquals(1, luceneIndexService.search("content", 1000).size())

        // jdbcTemplate and authorRepository are never touched on this
        // path - null is safe and, better than a mock, makes any future
        // usage on this path fail this test loudly instead of silently
        // passing against a fake.
        new AdminService(teiRepo, teiFileRepository, teiDivRepository, teiFileDbService,
                null, luceneIndexService, null, eventsPublisher)
                .pruneRemovedTeis(new StringWriter())

        // The DB prune happened...
        assertEquals(["creanga/povesti.xml"], teiFileDbService.deleted)

        // ...the removal event was emitted...
        assertEquals(1, this.removalEvents.size())
        assertTrue(this.removalEvents[0].path == "creanga/povesti")

        // ...and the Lucene index was never touched: the removed book's
        // documents are still searchable. Dropping index content is a
        // manual-only operation (LuceneIndexService.removeOpus) by the
        // search-data-is-precious policy.
        assertEquals(1, luceneIndexService.search("content", 1000).size())
        assertTrue(luceneIndexService.search("content", 1000)*.getUrl().contains("creanga/povesti"))
    }
}
