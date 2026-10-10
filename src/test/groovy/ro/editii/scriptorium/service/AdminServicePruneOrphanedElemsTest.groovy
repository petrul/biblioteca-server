package ro.editii.scriptorium.service

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.scheduled.NoWriter
import ro.editii.scriptorium.tei.TeiRepo

import static org.junit.jupiter.api.Assertions.assertEquals


/**
 * Pure Groovy-fakes unit test (no Spring context, no Mockito - same style
 * as VectorTextSearchServiceTest) for pruneOrphanedElems() - the true
 * FK-orphan sweep: tei_elem rows whose tei_file row is gone entirely, which
 * pruneRemovedTeis cannot see because it walks tei_file rows (precisely
 * what is missing here). The orphaned-opera query and the leftover bulk
 * delete are JPQL anti-joins on TeiDivRepository (their SQL is covered by
 * AdminServiceOrphanSweepSqlTest), so this test fakes those and asserts
 * the per-item isolation: one failing orphan tree must not starve the
 * rest of the sweep.
 */
class AdminServicePruneOrphanedElemsTest {

    /** Fake TeiFileDbService - records every tree it was asked to delete. */
    private static class RecordingTeiFileDbService extends TeiFileDbService {
        List<Long> deletedRootIds = []
        Set<Long> failOnIds = [] as Set

        RecordingTeiFileDbService() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null)
        }

        void deleteOrphanedElems(TeiDiv root) {
            this.deletedRootIds << root.getId()
            if (root.getId() in this.failOnIds)
                throw new RuntimeException("simulated mid-tree failure")
        }
    }

    // Fake repository state - the map-coerced TeiDivRepository below reads it.
    List<Long> orphanedOperaIds = []
    Map<Long, TeiDiv> divsById = [:]
    int leftoverRows = 0
    int leftoverSweeps = 0
    int opusRemovedSignals = 0

    RecordingTeiFileDbService teiFileDbService
    AdminService adminService

    static TeiDiv orphanOpus(long id) {
        final div = new TeiDiv()
        div.setId(id)
        return div
    }

    @BeforeEach
    void setUp() {
        final teiDivRepository = [
                findOrphanedOperaIds     : { -> this.orphanedOperaIds },
                findById                 : { Object id -> Optional.ofNullable(this.divsById[(Long) id]) },
                deleteElemsWithoutTeiFile: { -> this.leftoverSweeps++; this.leftoverRows },
        ] as TeiDivRepository
        final eventsPublisher = [signalOpusRemoved: { dto -> this.opusRemovedSignals++ }] as TextbaseEventsPublisher
        this.teiFileDbService = new RecordingTeiFileDbService()
        // The JdbcTemplate and LuceneIndexService are never reached by
        // pruneOrphanedElems - null makes any accidental use fail loudly.
        this.adminService = new AdminService([:] as TeiRepo, [:] as TeiFileRepository, teiDivRepository,
                this.teiFileDbService, null, null, [:] as AuthorRepository, eventsPublisher)
    }

    @Test
    void deletesEveryOrphanedOpusTreeAndTheLeftoverRows() {
        this.orphanedOperaIds = [5L, 7L]
        this.divsById = [(5L): orphanOpus(5L), (7L): orphanOpus(7L)]
        // The leftover bulk delete reports 3 rows no root could reach.
        this.leftoverRows = 3

        adminService.pruneOrphanedElems(new NoWriter())

        assertEquals([5L, 7L].toSet(), this.teiFileDbService.deletedRootIds.toSet())
        // The leftover anti-join delete always runs - orphaned rows without
        // any root are exactly the ones only it can reach.
        assertEquals(1, this.leftoverSweeps)
    }

    @Test
    void oneFailingOrphanTreeDoesNotStarveTheRestOrTheLeftoverSweep() {
        this.orphanedOperaIds = [5L, 7L]
        this.divsById = [(5L): orphanOpus(5L), (7L): orphanOpus(7L)]
        // The first tree fails mid-delete, the second must still go
        // through - same per-file isolation reasoning as pruneRemovedTeis,
        // where one poison file must not starve the rest.
        this.teiFileDbService.failOnIds = [5L] as Set

        adminService.pruneOrphanedElems(new NoWriter())

        assertEquals([5L, 7L].toSet(), this.teiFileDbService.deletedRootIds.toSet())
        assertEquals(1, this.leftoverSweeps)
    }

    @Test
    void aVanishedOrphanIdIsSkippedAndTheSweepStillCompletes() {
        // The id disappeared between the anti-join and findById (another
        // sweeper, or a concurrent prune) - empty Optional, not a failure.
        this.orphanedOperaIds = [5L]

        adminService.pruneOrphanedElems(new NoWriter())

        assertEquals([], this.teiFileDbService.deletedRootIds)
        assertEquals(1, this.leftoverSweeps)
        assertEquals(0, this.opusRemovedSignals)
    }
}
