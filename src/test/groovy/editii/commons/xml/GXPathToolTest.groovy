package editii.commons.xml

import org.junit.jupiter.api.Test
import org.w3c.dom.Node

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

    /**
     * The getXPath bracket-selector bug (see README's "Bugs & performance
     * debt (rethink later)"): the positional bracket is computed as
     * [nrPreviousDivs + 1] - counting previous siblings NAMED DIV - but
     * XPath's [n] predicate counts SAME-NAME siblings. Deliberately a
     * FAILING test: it asserts the correct semantics, so it stays red
     * until the bug is consciously fixed (fixing it changes the stored
     * tei_elem.xpath scheme, so it must not happen by drive-by).
     *
     * Two faces of the same bug:
     * 1. an element with div siblings before it gets an over-counted
     *    bracket (a first-and-only <p> after two <div>s -> "p[3]", a
     *    path that matches NOTHING);
     * 2. an element with same-name siblings but no div siblings gets NO
     *    bracket at all - an ambiguous path xpath_one would reject.
     */
    @Test
    void testGetXPathBracketCountsSameNameSiblingsNotDivs() {
        // Case 1: the first and only <p>, preceded by two <div> siblings -
        // correct XPath is p[1]; the current implementation produces
        // p[3] (counting the divs).
        def xt = new XpathTool(
            """<body>
                 <div>one</div>
                 <div>two</div>
                 <p id="target">hello</p>
               </body>""")
        final target = xt.xpath_one('/body/p[@id="target"]')
        assert XpathTool.getXPath(target) == '/body/p[1]'

        // Case 2: same-name siblings, no div siblings - the second <p>'s
        // correct XPath is p[2]; the current implementation emits no
        // bracket ("/body/p"), which xpath_one rejects as ambiguous.
        xt = new XpathTool(
            """<body>
                 <p>first</p>
                 <p id="second">second</p>
               </body>""")
        final second = xt.xpath_one('/body/p[@id="second"]')
        assert XpathTool.getXPath(second) == '/body/p[2]'
    }
}
