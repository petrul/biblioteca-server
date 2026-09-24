package ro.editii.scriptorium.service

import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.enrichment.EnrichmentService
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.scheduled.NoWriter
import ro.editii.scriptorium.search.lucene.LuceneIndexService
import ro.editii.scriptorium.tei.TeiRepo

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.mockito.ArgumentMatchers.anyString


/**
 * Pure Mockito unit test (no Spring context - same harness as
 * AdminServicePruneRemovedTeisTest) for pruneOrphanedElems() - the true
 * FK-orphan sweep: tei_elem rows whose tei_file row is gone entirely, which
 * pruneRemovedTeis cannot see because it walks tei_file rows (precisely
 * what is missing here). The orphaned-opera query and the leftover bulk
 * delete are native anti-joins through AdminService's JdbcTemplate, so
 * this test stubs those and asserts the per-item isolation: one failing
 * orphan tree must not starve the rest of the sweep.
 */
class AdminServicePruneOrphanedElemsTest {

    final TeiRepo teiRepo = Mockito.mock(TeiRepo)
    final TeiFileRepository teiFileRepository = Mockito.mock(TeiFileRepository)
    final TeiDivRepository teiDivRepository = Mockito.mock(TeiDivRepository)
    final TeiFileDbService teiFileDbService = Mockito.mock(TeiFileDbService)
    final JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate)
    final LuceneIndexService luceneIndexService = Mockito.mock(LuceneIndexService)
    final EnrichmentService enrichmentService = Mockito.mock(EnrichmentService)
    final TextbaseEventsPublisher eventsPublisher = Mockito.mock(TextbaseEventsPublisher)

    final AdminService adminService = new AdminService(teiRepo, teiFileRepository, teiDivRepository,
            teiFileDbService, jdbcTemplate, luceneIndexService, enrichmentService, eventsPublisher)

    static TeiDiv orphanOpus(long id) {
        final div = new TeiDiv()
        div.setId(id)
        return div
    }

    @Test
    void deletesEveryOrphanedOpusTreeAndTheLeftoverRows() {
        Mockito.when(jdbcTemplate.queryForList(anyString())).thenReturn([[id: 5L], [id: 7L]])
        Mockito.when(teiDivRepository.findById(5L)).thenReturn(java.util.Optional.of(orphanOpus(5L)))
        Mockito.when(teiDivRepository.findById(7L)).thenReturn(java.util.Optional.of(orphanOpus(7L)))
        // The leftover bulk delete reports 3 rows no root could reach.
        Mockito.when(jdbcTemplate.update(anyString())).thenReturn(3)

        adminService.pruneOrphanedElems(new NoWriter())

        final ArgumentCaptor<TeiDiv> captor = ArgumentCaptor.forClass(TeiDiv)
        Mockito.verify(teiFileDbService, Mockito.times(2)).deleteOrphanedElems(captor.capture())
        assertEquals([5L, 7L].toSet(), captor.getAllValues().collect { it.getId() }.toSet())
        // The leftover anti-join delete always runs - orphaned rows without
        // any root are exactly the ones only it can reach.
        Mockito.verify(jdbcTemplate, Mockito.times(1)).update(anyString())
    }

    @Test
    void oneFailingOrphanTreeDoesNotStarveTheRestOrTheLeftoverSweep() {
        Mockito.when(jdbcTemplate.queryForList(anyString())).thenReturn([[id: 5L], [id: 7L]])
        Mockito.when(teiDivRepository.findById(5L)).thenReturn(java.util.Optional.of(orphanOpus(5L)))
        Mockito.when(teiDivRepository.findById(7L)).thenReturn(java.util.Optional.of(orphanOpus(7L)))
        Mockito.when(jdbcTemplate.update(anyString())).thenReturn(0)
        // thenAnswer (not an argThat matcher) decides per id: the first
        // tree fails mid-delete, the second must still go through - same
        // per-file isolation reasoning as pruneRemovedTeis, where one
        // poison file must not starve the rest.
        Mockito.when(teiFileDbService.deleteOrphanedElems(Mockito.any(TeiDiv))).thenAnswer({ invocation ->
            if (((TeiDiv) invocation.getArgument(0)).getId() == 5L)
                throw new RuntimeException("simulated mid-tree failure")
            return null
        })

        adminService.pruneOrphanedElems(new NoWriter())

        final ArgumentCaptor<TeiDiv> captor = ArgumentCaptor.forClass(TeiDiv)
        Mockito.verify(teiFileDbService, Mockito.times(2)).deleteOrphanedElems(captor.capture())
        assertEquals([5L, 7L].toSet(), captor.getAllValues().collect { it.getId() }.toSet())
        Mockito.verify(jdbcTemplate, Mockito.times(1)).update(anyString())
    }

    @Test
    void aVanishedOrphanIdIsSkippedAndTheSweepStillCompletes() {
        // The id disappeared between the anti-join and findById (another
        // sweeper, or a concurrent prune) - empty Optional, not a failure.
        Mockito.when(jdbcTemplate.queryForList(anyString())).thenReturn([[id: 5L]])
        Mockito.when(teiDivRepository.findById(5L)).thenReturn(java.util.Optional.empty())
        Mockito.when(jdbcTemplate.update(anyString())).thenReturn(0)

        adminService.pruneOrphanedElems(new NoWriter())

        Mockito.verify(teiFileDbService, Mockito.never()).deleteOrphanedElems(Mockito.any())
        Mockito.verify(jdbcTemplate, Mockito.times(1)).update(anyString())
        Mockito.verify(eventsPublisher, Mockito.never()).signalOpusRemoved(Mockito.any())
    }
}
