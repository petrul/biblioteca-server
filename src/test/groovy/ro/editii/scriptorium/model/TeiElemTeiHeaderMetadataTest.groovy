package ro.editii.scriptorium.model

import org.junit.jupiter.api.Test
import org.w3c.dom.Node
import ro.editii.scriptorium.tei.TeiRepo

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit test for TeiElem's teiHeader metadata accessors
 * (getTeiLanguage/getLicense/getSourceDesc): the teiHeader lives outside
 * the element, so these must query the cached parsed document, not the
 * element's detached deep copy - the old getNode()-rooted form evaluated
 * absolute /tei:TEI/... paths against the copy's empty owner document and
 * silently never matched, under any engine (Xalan included).
 */
class TeiElemTeiHeaderMetadataTest {

    static final String TEST_FILE = 'testrepo/ro/Creanga-Amintiri_din_copilarie.xml'
    static final String TEI_NS = 'http://www.tei-c.org/ns/1.0'

    private TeiDiv teiDiv() {
        final TeiDiv div = new TeiDiv()
        div.setTeiFile(new TeiFile(filename: TEST_FILE))
        div.setTeiRepo([
                getStreamForName: { String name ->
                    TeiElemTeiHeaderMetadataTest.class.classLoader.getResourceAsStream(name)
                },
                getName       : { -> 'testrepo' },
        ] as TeiRepo)
        return div
    }

    @Test
    void 'teiHeader language, license and sourceDesc are found in the parsed document'() {
        final TeiDiv div = teiDiv()

        // <language ident="ro">ro</language> under profileDesc
        assertEquals('ro', div.getTeiLanguage().trim())

        // publicationStmt is now matched at all - before the fix the query
        // returned nothing (null), even though this file has one
        assertNotNull(div.getLicense(), 'publicationStmt must be found')

        // sourceDesc: a detached copy of the real element, with its ptr
        // target attribute intact
        final Node sourceDesc = div.getSourceDesc()
        assertNotNull(sourceDesc)
        final ptr = sourceDesc.getElementsByTagNameNS(TEI_NS, 'ptr').item(0)
        assertNotNull(ptr)
        assertTrue(ptr.getAttributes().getNamedItem('target').getNodeValue().contains('wikisource.org'))
    }
}
