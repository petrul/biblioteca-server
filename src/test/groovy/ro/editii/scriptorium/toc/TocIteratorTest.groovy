package ro.editii.scriptorium.toc

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiElem

import static org.junit.jupiter.api.Assertions.*

class TocIteratorTest {
    @Test
    void visitsOnlyLeavesInDocumentOrder() {
        def first = new TeiDiv(id: 3L)
        def second = new TeiDiv(id: 4L, dbChildren: [])
        def last = new TeiDiv(id: 5L)
        def branch = new TeiDiv(id: 2L, dbChildren: [first, new TeiElem(id: 6L), second])
        def root = new TeiDiv(id: 1L, dbChildren: [branch, new TeiElem(id: 7L), last])
        def iterator = new TocIterator(root)
        assertTrue(iterator.hasNext())
        assertTrue(iterator.hasNext())
        assertSame(first, iterator.next())
        assertSame(second, iterator.next())
        assertSame(last, iterator.next())
        assertFalse(iterator.hasNext())
        assertThrows(NoSuchElementException) { iterator.next() }
        // Regular TOC navigation must still include parents.
        assertEquals([root, branch, first, second, last], new Toc(root).toList())
    }

    @Test
    void rootWithoutChaptersIsItsOwnLeaf() {
        def root = new TeiDiv(id: 1L)
        def iterator = new TocIterator(root)
        assertSame(root, iterator.next())
        assertFalse(iterator.hasNext())
    }

    @Test
    void emptyTocIsExhausted() {
        def iterator = new TocIterator(new Toc(null))
        assertFalse(iterator.hasNext())
        assertThrows(NoSuchElementException) { iterator.next() }
    }
}
