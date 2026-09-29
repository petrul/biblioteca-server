package editii.commons.xml

import org.junit.jupiter.api.Test
import org.w3c.dom.Node

import static org.junit.jupiter.api.Assertions.assertSame
import static ro.editii.scriptorium.GTestUtil.*

class GXPathToolTest {

    /**
     * https://yt.scriptorium.ro/youtrack/issue/TB-219
     */
    @Test
    void testRemovingLabelStillKeepsTheHeadParseable() {
        final str =
            """<head>
                 <label>Cap. 1</label>
                 Manifeste și amintiri politice
             </head>
             """

        def xt = new XpathTool(str)
        final headList = xt.applyXpathForNodeSet('/head')
        assert headList.getLength() == 1
        def head = headList.item(0)
        assert head != null

        head = DomTool.deepCopy(head)

        xt = new XpathTool(head)
        final textNodes = xt.applyXpathForNodeSet(".//text()")
        assert textNodes.getLength() == 3
        for (int i = 0; i < textNodes.getLength(); i++) {
            final txt = textNodes.item(i)
            p txt
        }
        assert head.getChildNodes().length == 3
        Node label = null
        for (int i = 0; i < head.getChildNodes().getLength(); i++) {
            final Node c = head.getChildNodes().item(i)
            p "$i - ${c.nodeName} - [${c.nodeValue}]"

            if (c.nodeName == 'label')
                label = c
        }
        // with label
        assert head.getTextContent().trim()
                .replaceAll('\n', ' ')
                .replaceAll('\\s+', ' ') == 'Cap. 1 Manifeste și amintiri politice'

        head.removeChild(label)
        assert head.getChildNodes().length == 2

        // label was removed
        assert head.getTextContent().trim() == 'Manifeste și amintiri politice'
    }

    @Test
    void testGetXPathBracketCountsSameNameSiblingsNotDivs() {
        // A singleton p after div siblings still needs the p[1] selector.
        def xt = new XpathTool(
            """<body>
                 <div>one</div>
                 <div>two</div>
                 <p id="target">hello</p>
               </body>""")
        final target = xt.xpath_one('/body/p[@id="target"]')
        assert XpathTool.getXPath(target) == '/body/p[1]'

        // Same-name siblings need a position even when there are no divs.
        xt = new XpathTool(
            """<body>
                 <p>first</p>
                 <p id="second">second</p>
               </body>""")
        final second = xt.xpath_one('/body/p[@id="second"]')
        assert XpathTool.getXPath(second) == '/body/p[2]'
    }

    @Test
    void getXPathCountsSameExpandedNamesAcrossDifferentPrefixes() {
        final xt = new XpathTool(
            """<body xmlns="http://www.tei-c.org/ns/1.0"
                       xmlns:tei="http://www.tei-c.org/ns/1.0">
                 <p>first</p>
                 <tei:p id="second">second</tei:p>
               </body>""")
        final second = xt.xpath_one('/tei:body/tei:p[2]')

        assert XpathTool.getXPath(second) == '/tei:body/tei:p[2]'
        assertSame(second, xt.xpath_one(XpathTool.getXPath(second)))
    }
}
