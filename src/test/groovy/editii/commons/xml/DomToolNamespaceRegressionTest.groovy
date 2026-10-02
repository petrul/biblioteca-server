package editii.commons.xml

import org.junit.jupiter.api.Test
import org.w3c.dom.Document

import javax.xml.parsers.DocumentBuilderFactory

import static org.junit.jupiter.api.Assertions.assertEquals

class DomToolNamespaceRegressionTest {

    @Test
    void deepCopyKeepsLegacyPrefixedNodesWhenJdkRejectsTheirMissingNamespace() {
        Document source = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().newDocument()
        def legacyNode = source.createElement('tei:div')
        legacyNode.textContent = 'legacy TEI content'
        source.appendChild(legacyNode)

        def copy = DomTool.deepCopy(legacyNode)

        assertEquals('tei:div', copy.nodeName)
        assertEquals('legacy TEI content', copy.textContent)
    }
}
