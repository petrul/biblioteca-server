package editii.commons.xml

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import ro.editii.scriptorium.Util

import static ro.editii.scriptorium.GTestUtil.*
import static editii.commons.xml.DomTool.*

import javax.imageio.ImageIO

class TeiDocumentTest {

    @Test
    void decodeBinaryObjectUsingPlainXPath() {
        final resourceName = 'testrepo/ro/Cantemir-Descrierea_Moldovei.xml'
        final stream = this.class.classLoader.getResourceAsStream(resourceName);
        final xt = new XpathTool(stream, resourceName)

        def nodeList = xt.applyXpathForNodeSet('//tei:binaryObject')
        println nodeList.length
        assert nodeList.length == 1
        nodeList = xt.applyXpathForNodeSet("//tei:binaryObject[@xml:id='d3e1954']")
        p nodeList.length
        assert nodeList.length == 1

        def str = xt.xpath("//tei:binaryObject[@xml:id='d3e1954']").trim()

        p str.length()

        assert str.startsWith("iVBORw0KGgoAAAANSUhEUgAABI4AAATECAIAAACCytnEABQnfUlEQVR4nOy9B7hWxbXwP+85")
        assert str.endsWith("qXFGwfCUT5bQ678le18vdC7dhxqQEzzM/w/dPsE0qAD6RwAAAABJRU5ErkJggg==")

        def bytes = Util.base64Decode(str)

        assert bytes != null
        assert bytes.length > 0

        final image = ImageIO.read(new ByteArrayInputStream(bytes))

        assert image != null
        assert image.width == 1166
        assert image.height == 1220
    }

    @Test
    void decodeBinaryObject() {
        final resourceName = 'testrepo/ro/Cantemir-Descrierea_Moldovei.xml'
        final stream = this.class.classLoader.getResourceAsStream(resourceName);
        final tei = new TeiDocument(stream, resourceName)

        try {
            tei.getBinaryObject('pulea')
            Assertions.fail('should fail')
        } catch (IllegalArgumentException e) {
            // ok
            assert e.message =~ /cannot find binary object by id/
        }

        final bytes = tei.getBinaryObject('d3e1954');

        assert bytes != null
        assert bytes.length > 0

        final image = ImageIO.read(new ByteArrayInputStream(bytes))

        assert image != null
        assert image.width == 1166
        assert image.height == 1220
    }

    /**
     * this test is actually triggered by TB-214
     * it sanctifies what looks like a bug in the Java DOM api.
     */
    @Test
    void relativeXpathWorks() {
        def alecsandriXml = "testrepo/ro/Alecsandri-Scrieri.xml"
        final teidoc = new TeiDocument(this.class.classLoader.getResourceAsStream(alecsandriXml), alecsandriXml)
        final body = teidoc.body

        assert teidoc.applyXpathForNodeSet(TeiDocument.XPATH_BODY).item(0) == body
        def xt = new XpathTool(body, true)

        // now do some assertions to show that XPathTool works rooted at nodes which are not the document root.
        assert xt.applyXpathForNodeSet(TeiDocument.XPATH_BODY).length == 0
        assert xt.applyXpathForNodeSet("/tei:body").item(0).nodeName == "body"
        assert xt.applyXpathForNodeSet("/tei:body").item(0).namespaceURI == 'http://www.tei-c.org/ns/1.0'
        assert xt.applyXpathForNodeSet("/tei:body/tei:div").length > 1

        assert xt.applyXpathForNodeSet("/tei:body/tei:div[1]/tei:head").length == 1
        assert xt.applyXpathForNodeSet("/tei:body/tei:div[1]/tei:head").item(0).textContent.contains('Manifeste și amintiri politice')
        assert xt.applyXpathForNodeSet("/tei:body/tei:div[1]/tei:head").item(0).textContent.contains('Cap. 1')

        final textUnderHeadNodeList = xt.applyXpathForNodeSet("/tei:body/tei:div[1]/tei:head//text()")
        final textUnderHead = DomTool.nodeList2Collection(textUnderHeadNodeList)
        assert textUnderHead.size() > 1

        // now compute the text under the head using a second xpathtool rooted at <head>
        final headNode = xt.applyXpathForNodeSet("/tei:body/tei:div[1]/tei:head").item(0)
        final textUnderHead1 = DomTool.nodeList2Collection(new XpathTool(headNode).applyXpathForNodeSet(".//text()"))

        assert textUnderHead.size() == textUnderHead1.size()
        assert textUnderHead.collect{it.textContent} == textUnderHead1.collect{it.textContent}

        assert textUnderHead.size() == 3

        // now try to make happen the bug where removing the <label>, leaves only one child text node.
        DomTool.removeSubnodesByNodenames(headNode, ['label'] as String[])

        def textsAgain = nodeList2Collection(new XpathTool(headNode, true).applyXpathForNodeSet(".//text()"))

        /*
            TB-214, historical: with the JDK's internal Xalan as the XPath
            engine (the silent XPathFactory.newInstance() fallback before
            XpathTool pinned Saxon), a DOM mutation like removeChild left
            Xalan's DTM stale, so './/text()' here returned a single text
            node. Saxon evaluates against the live DOM and returns the
            correct two. The recursive workaround below is kept because
            production code still uses it.
         */
        assert textsAgain.size() == 2

        // ... and this is the workaround we will use in the code
        NodeList nodeList = XpathTool.from(headNode, true).select( (Node it) -> it.nodeType == Node.TEXT_NODE)

        assert nodeList.length == 2
        assert nodeList2Collection(nodeList).collect{it.textContent}.join(" ").contains('Manifeste și amintiri politice')


        // and finally assert that re-parsing the text of what has been left after the removing of label
        // generates a DOM tree which is normally query-able using the .//text() construct.
        xt = XpathTool.from("""
            <head xmlns="http://www.tei-c.org/ns/1.0">
                 
                 Manifeste și amintiri politice
             </head>
        """)
        assert xt.applyXpathForNodeSet(".//text()").length == 1
        assert xt.applyXpathForNodeSet(".//text()").item(0).textContent.contains('Manifeste și amintiri politice')
    }


    /**
     * in java equal string literals are strictly identical;
     * but strings that may come from various sources such as operations
     * are not identical even though they may be value-equal (which makes sense).
     */
    @Test
    void testString() {
        final String s1 = 'lalala';
        final String s2 = 'lalala';
        final String s3 = 'lala' + 'la'
        assert s1 === s2
        assert s1 == s2
        assert s1 !== s3 // not strict identity
        assert s1 == s3 // value equality
    }

}