package ro.editii.scriptorium.model

import editii.commons.xml.TeiDocument
import editii.commons.xml.XpathTool
import org.junit.jupiter.api.Test
import org.w3c.dom.Node
import ro.editii.scriptorium.tei.TeiRepo
import ro.editii.scriptorium.tei.TeifileParser

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull

/**
 * Unit test for TeiElem.getNode()'s positional domPath fast path and its
 * xpath fallback (see TeiElem.domPath): resolution must use the domPath
 * column when present (pure getChildNodes() hops, no XPath engine), and
 * fall back to the stored xpath when domPath is null (rows imported before
 * the column existed) or stale (document changed since import).
 *
 * Each scenario proves which path was taken by giving the resolution an
 * input that would make the other path fail: a good domPath with an xpath
 * that cannot match can only succeed via domPath, and a good xpath with a
 * broken domPath can only succeed via the fallback.
 */
class TeiElemDomPathResolutionTest {

    static final String TEST_FILE = 'testrepo/ro/Creanga-Amintiri_din_copilarie.xml'

    private TeiRepo repo() {
        // Minimal stub: TeiElem.parseTeiFile() only needs getStreamForName()
        // (and TeiRepo itself only for the null check in getNode()).
        return [
                getStreamForName: { String name ->
                    TeiElemDomPathResolutionTest.class.classLoader.getResourceAsStream(name)
                },
                getName       : { -> 'testrepo' },
        ] as TeiRepo
    }

    /** the first body div, located by xpath in a freshly parsed document - the oracle */
    private static Node firstBodyDivOracle() {
        final InputStream is = TeiElemDomPathResolutionTest.class.classLoader.getResourceAsStream(TEST_FILE)
        final TeiDocument doc = new TeiDocument(is, TEST_FILE)
        return doc.applyXpathForNodeSet(TeiDocument.XPATH_BODY + '/tei:div[1]').item(0)
    }

    /**
     * a TeiDiv carrying exactly what TeifileParser.parcurge_rec stores at
     * import (xpath + domPath computed with the same helpers it uses), with
     * the given overrides
     */
    private TeiDiv teiDiv(Map overrides = [:]) {
        final Node oracle = firstBodyDivOracle()
        final TeiDiv div = new TeiDiv()
        div.setTeiFile(new TeiFile(filename: TEST_FILE))
        div.setTeiRepo(repo())
        div.setXpath(XpathTool.getXPathRelativeTo(oracle, TeifileParser.TEI_BODY_XPATH_PREFIX))
        div.setDomPath(XpathTool.getDomPath(oracle))
        div.setName('div')
        overrides.each { k, v -> div."$k" = v }
        return div
    }

    @Test
    void 'domPath is used for resolution - a stored xpath that cannot match does not matter'() {
        // this xpath would make the fallback throw "Expected exactly one
        // div" - so a passing resolution proves domPath was used
        final TeiDiv div = teiDiv(xpath: '/tei:div[999]')

        final Node node = div.node
        assertNotNull(node)
        assertEquals(firstBodyDivOracle().textContent, node.textContent)
    }

    @Test
    void 'xpath is the fallback when domPath is null - rows imported before the column existed'() {
        final TeiDiv div = teiDiv(domPath: null)

        final Node node = div.node
        assertNotNull(node)
        assertEquals(firstBodyDivOracle().textContent, node.textContent)
    }

    @Test
    void 'a stale domPath falls back to xpath instead of resolving a wrong node'() {
        // out of range: resolveDomPath returns null -> fallback to xpath
        final TeiDiv outOfRange = teiDiv(domPath: '999/999/999')
        assertEquals(firstBodyDivOracle().textContent, outOfRange.node.textContent)

        // in range but the wrong node (the document's tei:TEI element, not
        // a div): the name check rejects it -> fallback to xpath
        final TeiDiv wrongNode = teiDiv(domPath: '1')
        assertEquals(firstBodyDivOracle().textContent, wrongNode.node.textContent)
    }

    @Test
    void 'concurrent requests do not race deferred DOM namespace initialization'() {
        def failures = Collections.synchronizedList([])
        def workers = (1..16).collect {
            Thread.start {
                try {
                    assertNotNull(teiDiv().node)
                } catch (Throwable failure) {
                    failures << failure
                }
            }
        }
        workers*.join()
        assertEquals([], failures)
    }
}
