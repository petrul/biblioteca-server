package ro.editii.scriptorium.search.lucene

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiElem
import ro.editii.scriptorium.service.ControllerTool
import ro.editii.scriptorium.service.DivService
import ro.editii.scriptorium.tei.TeiResourceNotFoundException

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Covers the two behaviors this session's Lucene work added on top of the
 * pre-existing reindexOpus() incremental-update path: buildIndex()'s
 * resumability (a full rebuild interrupted mid-way resumes from its last
 * committed page instead of redoing the whole corpus - see
 * LuceneIndexService's own class doc comment) and removeOpus() (purging
 * one opus without touching a similarly-prefixed sibling).
 *
 * Mocks TeiDivRepository/DivService/ControllerTool rather than building
 * real TeiDiv/TeiElem trees through TeifileParser - toDocument() only ever
 * calls a handful of getters on them, so a real object graph (Author,
 * TeiFile, URL computation) would add setup cost without adding coverage
 * of anything this test is actually about. Directory I/O is real (a real
 * FSDirectory in a JUnit temp dir) - that's the part actually worth
 * exercising for real, since commit durability is the whole point.
 */
class LuceneIndexServiceResumeTest {

    @TempDir
    Path tempDir

    final TeiDivRepository teiDivRepository = Mockito.mock(TeiDivRepository)
    final DivService divService = Mockito.mock(DivService)
    final ControllerTool controllerTool = Mockito.mock(ControllerTool)

    LuceneIndexService newService() {
        return new LuceneIndexService(tempDir.toString(), false, 0d, teiDivRepository, divService, controllerTool)
    }

    static TeiDiv opusMock(String path) {
        final opus = Mockito.mock(TeiDiv)
        Mockito.when(opus.getCompletePath()).thenReturn(path)
        return opus
    }

    static TeiElem paraMock(String path, String head) {
        final div = Mockito.mock(TeiDiv)
        Mockito.when(div.getHead()).thenReturn(head)
        final para = Mockito.mock(TeiElem)
        Mockito.when(para.getCompletePath()).thenReturn(path)
        Mockito.when(para.getDiv()).thenReturn(div)
        return para
    }

    @Test
    void resumeIndexContinuesFromTheCheckpointInsteadOfRedoingEarlierPages() {
        final int pageSize = LuceneIndexService.OPERA_PAGE_SIZE
        final page0Opera = (0..<pageSize).collect { opusMock("opus-${it}") }
        final page1Opera = (pageSize..<(pageSize + 3)).collect { opusMock("opus-${it}") }

        Mockito.when(teiDivRepository.findOpera(PageRequest.of(0, pageSize)))
                .thenReturn(new PageImpl<>(page0Opera, PageRequest.of(0, pageSize), pageSize + 3))
        // Simulates the process getting killed before page 1 ever commits -
        // findOpera itself throwing is a convenient way to abort buildIndex
        // partway through without needing a real second JVM/process.
        Mockito.when(teiDivRepository.findOpera(PageRequest.of(1, pageSize)))
                .thenThrow(new RuntimeException("simulated crash before page 1 commits"))

        page0Opera.each { opus ->
            // paraMock() does its own complete when()/thenReturn() pairs -
            // never call it (or anything else touching a mock) as an
            // argument to thenReturn() itself, that corrupts Mockito's
            // Groovy stubbing state (UnfinishedStubbingException). Resolve
            // it to a plain value first instead.
            final paragraphs = [paraMock("${opus.getCompletePath()}/p0", "head for ${opus.getCompletePath()}")]
            Mockito.when(divService.getParagraphs(opus)).thenReturn(paragraphs)
        }
        Mockito.when(controllerTool.teiElemToString(Mockito.any())).thenReturn("some real paragraph text")

        final service = newService()

        assertThrows(RuntimeException) { service.rebuildIndex() }

        // Page 0 already committed and searchable despite the "crash" -
        // not stuck empty until the whole corpus finishes.
        assertTrue(service.isAvailable())
        assertEquals(pageSize, service.search("some", 1000).size())
        final Map<String, String> commitDataAfterCrash = service.readCommitData()
        assertEquals("1", commitDataAfterCrash.get(LuceneIndexService.COMMIT_DATA_NEXT_PAGE))
        assertEquals("false", commitDataAfterCrash.get(LuceneIndexService.COMMIT_DATA_REBUILD_COMPLETE))

        // "Restart": a fresh service instance pointed at the same directory,
        // exactly what autoBuildIndexOnStartup does after a real restart.
        // doReturn().when(), not when(mock.method()).thenReturn(): the
        // latter would actually invoke findOpera(page 1) to register the
        // new stub, re-triggering the thenThrow() stubbed above instead of
        // replacing it.
        Mockito.doReturn(new PageImpl<>(page1Opera, PageRequest.of(1, pageSize), pageSize + 3))
                .when(teiDivRepository).findOpera(PageRequest.of(1, pageSize))
        page1Opera.each { opus ->
            final paragraphs = [paraMock("${opus.getCompletePath()}/p0", "head for ${opus.getCompletePath()}")]
            Mockito.when(divService.getParagraphs(opus)).thenReturn(paragraphs)
        }

        final resumed = newService()
        resumed.resumeIndex(1)

        // Called exactly once total (during the crashed attempt) - never
        // re-walked during the resume, proving the resume didn't redo it.
        Mockito.verify(teiDivRepository, Mockito.times(1)).findOpera(PageRequest.of(0, pageSize))

        assertEquals(pageSize + 3, resumed.search("some", 1000).size())
        final Map<String, String> commitDataAfterResume = resumed.readCommitData()
        assertEquals("true", commitDataAfterResume.get(LuceneIndexService.COMMIT_DATA_REBUILD_COMPLETE))
    }

    @Test
    void removeOpusPurgesOnlyThatOpusNotASimilarlyPrefixedSibling() {
        Mockito.when(controllerTool.teiElemToString(Mockito.any())).thenReturn("keeper content")

        final opusA = opusMock("seneca/de-vita")
        // Deliberately a URL that starts with opusA's own path as a plain
        // string prefix, but is a distinct opus - a naive prefix match
        // (rather than removeOpus's exact-match-or-slash-prefix TermQuery+
        // PrefixQuery pair) would wrongly delete this one too.
        final opusB = opusMock("seneca/de-vita-longa")

        final parasA = [
                paraMock("seneca/de-vita/p0", "A1"),
                paraMock("seneca/de-vita/p1", "A2"),
        ]
        final parasB = [
                paraMock("seneca/de-vita-longa/p0", "B1"),
        ]
        Mockito.when(divService.getParagraphs(opusA)).thenReturn(parasA)
        Mockito.when(divService.getParagraphs(opusB)).thenReturn(parasB)

        final service = newService()
        service.reindexOpus(opusA)
        service.reindexOpus(opusB)

        assertEquals(3, service.search("keeper", 1000).size())

        service.removeOpus("seneca/de-vita")

        final remaining = service.search("keeper", 1000)
        assertEquals(1, remaining.size())
        assertEquals("seneca/de-vita-longa/p0", remaining[0].getUrl())
    }

    @Test
    void buildIndexSkipsAnOpusWhoseSourceVanishedAndStillCompletes() {
        // The prod failure this guards against: one stale row (its TEI file
        // gone from the repos, prune not run yet) used to abort the whole
        // build via DivService.getParagraphs - the per-paragraph try/catch
        // only covered toDocument, not the paragraph derivation itself.
        final goodOpus = opusMock("bacon/of_gardens")
        final ghostOpus = opusMock("bcucluj/fcs_brv10")
        final int pageSize = LuceneIndexService.OPERA_PAGE_SIZE
        Mockito.when(teiDivRepository.findOpera(PageRequest.of(0, pageSize)))
                .thenReturn(new PageImpl<>([goodOpus, ghostOpus], PageRequest.of(0, pageSize), 2))

        // Resolve paraMock() to a plain value FIRST - it does its own
        // when()/thenReturn() pairs, and calling anything that touches a
        // mock as an argument to thenReturn() itself corrupts Mockito's
        // Groovy stubbing state (UnfinishedStubbingException - see the
        // comment in the resume test above).
        final goodParagraphs = [paraMock("bacon/of_gardens/p0", "gardens head")]
        Mockito.when(divService.getParagraphs(goodOpus)).thenReturn(goodParagraphs)
        Mockito.when(divService.getParagraphs(ghostOpus))
                .thenThrow(new TeiResourceNotFoundException("bcucluj_fcs_brv10_1.tei.xml"))
        Mockito.when(controllerTool.teiElemToString(Mockito.any())).thenReturn("gardens paragraph text")

        final service = newService()
        final int indexed = service.rebuildIndex()

        // The good opus is indexed, the ghost is skipped, and the build
        // still finishes - REBUILD_COMPLETE=true is what keeps
        // autoBuildIndexOnStartup from pointlessly rebuilding again on the
        // next restart.
        assertEquals(1, indexed)
        assertEquals(1, service.search("gardens", 10).size())
        final Map<String, String> commitData = service.readCommitData()
        assertEquals("true", commitData.get(LuceneIndexService.COMMIT_DATA_REBUILD_COMPLETE))
    }
}
