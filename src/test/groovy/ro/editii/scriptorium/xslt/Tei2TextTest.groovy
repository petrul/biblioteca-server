package ro.editii.scriptorium.xslt

import editii.commons.xml.DomTool
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * xslt/tei2text.xsl. Document mode (the .txt export, DAV, MCP) puts every
 * block on its own line. The default mode is a paragraph's indexed text:
 * Lucene, grep, quote fragments and the vectors' text_sha256 are computed
 * from it, so its output is pinned here byte for byte.
 */
class Tei2TextTest {

    static final String TEI = 'xmlns="http://www.tei-c.org/ns/1.0"'

    // one transformer, reused like the server's per-thread one: parameters stick
    static final transformer = XsltTool.getTransformer(
            Tei2TextTest.classLoader.getResourceAsStream('xslt/tei2text.xsl'), 'xslt/tei2text.xsl')

    /** As the server passes it: a detached copy of one element. */
    static String render(String xml, boolean document) {
        final Element parsed = (XsltTool.parseInputStreamToNode(new ByteArrayInputStream(xml.getBytes('UTF-8'))) as org.w3c.dom.Document).documentElement
        XsltTool.apply(transformer, DomTool.deepCopy(parsed), [document: String.valueOf(document)])
    }

    @Test
    void aDivisionAsADocumentKeepsEveryBlockApart() {
        final xml = """<div $TEI type="div2">
               <head>Of Gardens</head>
               <subtitle>AN ESSAY BY FRANCIS LORD BACON</subtitle>
               <subtitle>A GARDEN</subtitle>
               <p rend="justify">GOD almighty first
                  planted a garden<note n="1">Genesis  2:8</note>.</p>
               <table><row><cell>Geleitwort</cell><cell>1</cell></row><row><cell>Der Satyr</cell><cell>14</cell></row></table>
               <div type="div3"><head>II</head>
                 <lg><l>Prima linie,</l><l>a doua<lb/>linie.</l></lg>
                 <p>king.</p>
               </div>
               <div type="div3"><p>These words</p><figure><binaryObject xml:id="f1">AAAA</binaryObject></figure></div>
            </div>"""
        assertEquals('''Of Gardens

AN ESSAY BY FRANCIS LORD BACON
A GARDEN
GOD almighty first planted a garden [Genesis 2:8].

Geleitwort 1
Der Satyr 14

II

Prima linie,
a doua
linie.

king.

These words

[IMAGE].''', render(xml, true))
    }

    @Test
    void paragraphTextIsUnchanged() {
        // expected strings produced by the stylesheet before document mode
        // existed - indexed and hashed text must not move
        assertEquals('GOD almighty first\n                  planted a garden [Genesis  2:8]\n.',
                render("""<p $TEI>GOD almighty first
                  planted a garden<note n="1">Genesis  2:8</note>.</p>""", false))
        assertEquals('Geleitwort1Der Satyr14',
                render("<table $TEI><row><cell>Geleitwort</cell><cell>1</cell></row><row><cell>Der Satyr</cell><cell>14</cell></row></table>", false))
        assertEquals('Prima linie,\na doua\nlinie.',
                render("<lg $TEI><l>Prima linie,</l><l>a doua<lb/>linie.</l></lg>", false))
        assertEquals('AN ESSAY BY FRANCIS LORD BACON', render("<subtitle $TEI>AN ESSAY BY FRANCIS LORD BACON</subtitle>", false))
    }

    @Test
    void theDocumentParameterDoesNotStickToTheNextCall() {
        render("<div $TEI><p>a</p><p>b</p></div>", true)
        assertEquals('Geleitwort1', render("<table $TEI><row><cell>Geleitwort</cell><cell>1</cell></row></table>", false))
    }
}
