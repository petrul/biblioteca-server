package ro.editii.scriptorium.toc

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiElem

/** The one-query TOC (Toc.fromRows) is the same TOC the per-division walk builds. */
class TocFromRowsTest {

    /** A work with nested divisions, siblings stored out of id order and paragraphs between them. */
    static Map work() {
        def s1 = new TeiDiv(id: 11L), s2 = new TeiDiv(id: 12L), s3 = new TeiDiv(id: 14L)
        def act1 = new TeiDiv(id: 10L, dbChildren: [s1, new TeiElem(id: 13L), s2])
        def act2 = new TeiDiv(id: 20L, dbChildren: [s3])
        def appendix = new TeiDiv(id: 30L, dbChildren: [])
        def root = new TeiDiv(id: 1L, dbChildren: [act1, new TeiElem(id: 2L), act2, appendix])
        def parents = [(s1): act1, (s2): act1, (s3): act2, (act1): root, (act2): root, (appendix): root]
        [root: root, divs: [root, act1, act2, appendix, s1, s2, s3], parents: parents]
    }

    static List<Toc.DivRow> rows(Map w, List<TeiDiv> order) {
        order.collect { new Toc.DivRow(it, w.parents[it]?.id) }
    }

    @Test
    void sameOrderAsTheWalkWhateverOrderTheRowsComeIn() {
        def w = work()
        def walked = new Toc(w.root).toList()*.id
        assert walked == [1L, 10L, 11L, 12L, 20L, 14L, 30L]
        assert Toc.fromRows(w.root, rows(w, w.divs)).toList()*.id == walked
        assert Toc.fromRows(w.root, rows(w, w.divs.reverse())).toList()*.id == walked
        assert Toc.fromRows(w.root, rows(w, w.divs.sort(false) { it.id })).size() == 7
    }

    @Test
    void prevAndNextWorkOnTheNewToc() {
        def w = work()
        def toc = Toc.fromRows(w.root, rows(w, w.divs))
        def s2 = w.divs.find { it.id == 12L }
        assert toc.prev(s2).id == 11L && toc.next(s2).id == 20L
        assert toc.prev(w.root) == null && toc.next(w.divs.find { it.id == 30L }) == null
    }

    @Test
    void rowsOutsideTheRootsSubtreeAreIgnored() {
        def w = work()
        def act2 = w.divs.find { it.id == 20L }
        assert Toc.fromRows(act2, rows(w, w.divs)).toList()*.id == [20L, 14L]
        assert Toc.fromRows(null, rows(w, w.divs)).size() == 0
    }

    @Test
    void survivesTheDiskCachesSerialization() {
        def w = work()
        def bytes = new ByteArrayOutputStream()
        new ObjectOutputStream(bytes).withCloseable { it.writeObject(Toc.fromRows(w.root, rows(w, w.divs))) }
        def back = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())).readObject() as Toc
        assert back.toList()*.id == [1L, 10L, 11L, 12L, 20L, 14L, 30L]
        assert back.next(back.toList()[1]).id == 11L
    }
}
