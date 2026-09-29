package editii.commons.xml

import org.junit.jupiter.api.Test
import org.w3c.dom.Node
import org.w3c.dom.NodeList

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Guards against the XPath engine silently regressing to the JDK's internal
 * Xalan: Saxon-HE ships no META-INF/services entry for
 * javax.xml.xpath.XPathFactory (only for TransformerFactory), so the old
 * XPathFactory.newInstance() call found no Saxon and quietly used Xalan -
 * whose per-evaluate() DOM2DTM rebuild (a full int[] copy of the document
 * per query) profiled as ~75% of reimport/reindex CPU and ~86% of allocation
 * churn. XpathTool._applyXpath now instantiates Saxon's factory directly;
 * this test pins that.
 */
class SaxonIsTheXpathEngineTest {

    @Test
    void "xpath engine is Saxon, not the JDK's internal Xalan"() {
        final tool = new XpathTool('<a><b>ok</b></a>')

        // run one evaluation so the lazily-created engine exists
        assertEquals('ok', tool.xpath('/a/b/text()'))

        assertNotNull(tool.xpathEngine, 'engine should exist after the first evaluation')
        assertTrue(tool.xpathEngine.startsWith('net.sf.saxon.'),
                "expected a Saxon XPath engine, got [${tool.xpathEngine}]")
    }

    /**
     * DivService.getBinaryObject evaluates a descendant search against a
     * TeiDocument rooted at a deep-copied, detached mid-tree div node
     * (DomTool.deepCopy returns the imported node without appending it to
     * its document). An absolute // path resolves against that empty
     * owner document and finds nothing under a spec-correct engine
     * (Saxon) - Xalan only made it work by rooting its DTM at the node -
     * so TeiDocument.getBinaryObject must use the relative .// form.
     * This pins exactly that against regression in either direction.
     */
    @Test
    void "binaryObject search works against a detached mid-tree root"() {
        final String path = 'testrepo/ro/Cantemir-Descrierea_Moldovei.xml'
        final InputStream is = this.class.classLoader.getResourceAsStream(path)
        assertNotNull(is, "missing test resource $path")
        final TeiDocument doc = new TeiDocument(is, path)

        // the opus div, exactly like DivService.getBinaryObject() sees it:
        // a deep copy of the mid-tree div node, not the document
        final Node opusDiv = doc.applyXpathForNodeSet(TeiDocument.XPATH_BODY + '/tei:div[1]').item(0)
        assertNotNull(opusDiv)
        final TeiDocument copied = new TeiDocument(DomTool.deepCopy(opusDiv))

        final NodeList found = copied.applyXpathForNodeSet(".//tei:binaryObject[@xml:id='d3e1954']")
        assertEquals(1, found.length, 'the opus copy must contain the binaryObject')

        final byte[] bytes = copied.getBinaryObject('d3e1954')
        assertNotNull(bytes)
        assertTrue(bytes.length > 0)
    }
}
