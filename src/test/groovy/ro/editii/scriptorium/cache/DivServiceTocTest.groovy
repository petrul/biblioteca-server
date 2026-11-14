package ro.editii.scriptorium.cache

import org.junit.jupiter.api.Test
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiFile
import ro.editii.scriptorium.service.DivService
import ro.editii.scriptorium.toc.Toc

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DivService's TOC: one query per build, one build per work however many
 * requests want it at once, kept in the disk cache, forgotten on demand.
 */
class DivServiceTocTest {

    def file = new TeiFile(id: 7L)
    def root = new TeiDiv(id: 1L, teiFile: file)
    def chapter = new TeiDiv(id: 2L, teiFile: file)
    int queries = 0
    CountDownLatch release = new CountDownLatch(0)

    DivService service() {
        def repo = [findById               : { Long id -> Optional.ofNullable(id == 1L ? root : null) },
                    findDivRowsByTeiFileId : { long tf -> queries++; release.await(10, TimeUnit.SECONDS); [new Toc.DivRow(root, null), new Toc.DivRow(chapter, 1L)] },
                    findOperaIds           : { -> [1L, 5L] }] as TeiDivRepository
        def s = new DivService(repo, null, null)
        s.cacheToc = new DiskCache(Files.createTempDirectory('toc-test').toFile())
        s.cacheManager = new ConcurrentMapCacheManager('toc')
        s
    }

    @Test
    void oneQueryBuildsTheTocAndTheDiskCacheKeepsIt() {
        def s = service()
        assert !s.hasToc(1L)
        assert s.getToc(1L).toList()*.id == [1L, 2L]
        assert queries == 1 && s.hasToc(1L)
        assert s.getToc(1L).toList()*.id == [1L, 2L] && queries == 1 // from disk
        s.forgetToc(1L)
        assert !s.hasToc(1L)
    }

    @Test
    void concurrentRequestsShareOneBuild() {
        def s = service()
        release = new CountDownLatch(1) // the build waits until all requests are in
        def pool = Executors.newFixedThreadPool(5)
        def results = (1..5).collect { pool.submit({ s.getToc(1L) } as java.util.concurrent.Callable) }
        Thread.sleep(300)
        release.countDown()
        def tocs = results.collect { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assert queries == 1
        assert tocs.every { it.toList()*.id == [1L, 2L] }
    }

    @Test
    void listsTheWorksWithoutATocInTheCache() {
        def s = service()
        assert s.operaMissingToc(10).missing() == [1L, 5L] && s.operaMissingToc(10).works() == 2
        s.getToc(1L)
        assert s.operaMissingToc(10).missing() == [5L]
        assert s.operaMissingToc(1).missing().size() == 1
    }
}
