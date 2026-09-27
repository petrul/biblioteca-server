package ro.editii.scriptorium.service

import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.dto.OpusRemovedDto
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiFile
import ro.editii.scriptorium.scheduled.NoWriter
import ro.editii.scriptorium.search.lucene.LuceneIndexService
import ro.editii.scriptorium.tei.TeiRepo
import org.springframework.jdbc.core.JdbcTemplate

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * Pure Mockito unit test (no Spring context - AdminService has no
 * Spring-specific behavior of its own beyond @Service/@RequiredArgsConstructor
 * wiring) for pruneRemovedTeis() - the "a source file disappeared from
 * the repo" path. Per the search-data-is-precious policy this prunes the
 * DB rows and emits the opusRemoved events, but never touches the Lucene
 * index (nor, downstream, the vectorizer's stored vectors): dropping
 * index/vector content is a manual-only operation.
 * reimportFresherTeis/reimportAllTeis/reimportFile are deliberately NOT
 * exercised here (unrelated to this change, already covered elsewhere) -
 * this test is specifically about the detection-of-absence + cascading
 * cleanup logic pruneRemovedTeis introduces.
 */
class AdminServicePruneRemovedTeisTest {

    final TeiRepo teiRepo = Mockito.mock(TeiRepo)
    final TeiFileRepository teiFileRepository = Mockito.mock(TeiFileRepository)
    final TeiDivRepository teiDivRepository = Mockito.mock(TeiDivRepository)
    final TeiFileDbService teiFileDbService = Mockito.mock(TeiFileDbService)
    final JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate)
    final LuceneIndexService luceneIndexService = Mockito.mock(LuceneIndexService)
    final AuthorRepository authorRepository = Mockito.mock(AuthorRepository)
    final TextbaseEventsPublisher eventsPublisher = Mockito.mock(TextbaseEventsPublisher)

    final AdminService adminService = new AdminService(teiRepo, teiFileRepository, teiDivRepository,
            teiFileDbService, jdbcTemplate, luceneIndexService, authorRepository, eventsPublisher)

    static TeiFile teiFile(long id, String filename) {
        final tf = new TeiFile()
        tf.setId(id)
        tf.setFilename(filename)
        return tf
    }

    static TeiDiv opus(String path) {
        final o = Mockito.mock(TeiDiv)
        Mockito.when(o.getCompletePath()).thenReturn(path)
        return o
    }

    @Test
    void leavesAFileAloneWhenItIsStillPresentOnDisk() {
        final stillThere = teiFile(1L, "still-there.xml")
        Mockito.when(teiRepo.list()).thenReturn(["still-there.xml"])
        Mockito.when(teiFileRepository.findAll()).thenReturn([stillThere])

        adminService.pruneRemovedTeis(new NoWriter())

        Mockito.verify(teiFileDbService, Mockito.never()).deleteTeiFile(Mockito.anyString())
        Mockito.verify(eventsPublisher, Mockito.never()).signalOpusRemoved(Mockito.any())
    }

    @Test
    void prunesDbAndSignalsRemovalButRetainsTheLuceneIndexForAFileNoLongerOnDisk() {
        final removedFile = teiFile(2L, "gone.xml")
        final opusOne = opus("conscience/gone-work-one")
        final opusTwo = opus("conscience/gone-work-two")

        Mockito.when(teiRepo.list()).thenReturn([]) // nothing on disk any more
        Mockito.when(teiFileRepository.findAll()).thenReturn([removedFile])
        Mockito.when(teiDivRepository.getOperaForTeiFileId(2L)).thenReturn([opusOne, opusTwo])

        adminService.pruneRemovedTeis(new NoWriter())

        Mockito.verify(teiFileDbService, Mockito.times(1)).deleteTeiFile("gone.xml")
        // "Search data is precious": the removed book's Lucene documents
        // are RETAINED - its urls 404 and unresolved hits are filtered at
        // search time (see VectorUtils); dropping index content is a
        // manual-only operation (LuceneIndexService.removeOpus).
        Mockito.verifyNoInteractions(luceneIndexService)

        final ArgumentCaptor<OpusRemovedDto> captor = ArgumentCaptor.forClass(OpusRemovedDto)
        Mockito.verify(eventsPublisher, Mockito.times(2)).signalOpusRemoved(captor.capture())
        final signaledPaths = captor.getAllValues().collect { it.getPath() }.toSet()
        assertEquals(["conscience/gone-work-one", "conscience/gone-work-two"].toSet(), signaledPaths)
    }

    @Test
    void aFailedPruneForOneRemovedFileDoesNotStopThePruneOfTheNext() {
        // One un-deletable row (bad FK state, a concurrent delete racing us,
        // whatever) must not abort the whole run: before the per-file
        // isolation in pruneRemovedTeis it did, and called from the
        // scheduler every 15s that meant one poison row kept EVERY removed
        // file's cleanup from ever completing.
        final brokenFile = teiFile(4L, "broken-gone.xml")
        final nextFile = teiFile(5L, "also-gone.xml")

        Mockito.when(teiRepo.list()).thenReturn([])
        Mockito.when(teiFileRepository.findAll()).thenReturn([brokenFile, nextFile])
        Mockito.when(teiDivRepository.getOperaForTeiFileId(4L)).thenReturn([])
        Mockito.when(teiDivRepository.getOperaForTeiFileId(5L)).thenReturn([])
        Mockito.doThrow(new RuntimeException("delete is having a bad day"))
                .when(teiFileDbService).deleteTeiFile("broken-gone.xml")

        adminService.pruneRemovedTeis(new NoWriter())

        Mockito.verify(teiFileDbService, Mockito.times(1)).deleteTeiFile("also-gone.xml")
    }
}
