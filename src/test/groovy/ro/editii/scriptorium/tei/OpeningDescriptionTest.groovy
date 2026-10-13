package ro.editii.scriptorium.tei

import org.junit.jupiter.api.Test
import org.w3c.dom.Node

import javax.xml.parsers.DocumentBuilderFactory

class OpeningDescriptionTest {

    private static Node opus(String xml) {
        final factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(true)
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes('UTF-8'))).documentElement
    }

    /** elster/konigliche opens with a centered figure whose image is base64 text. */
    @Test
    void embeddedImageDataIsNotTheDescription() {
        final description = TeifileParser.openingDescription(opus("""
            <div type="div1">
                <head>Königliche Hoheit</head>
                <p rend="center"><figure><binaryObject encoding="base64" mimeType="image/jpg">/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9
                    PDkzODdASFxOQERXRTc4</binaryObject></figure></p>
                <p>Das Gesetz des Lebens.</p>
                <p>Zweiter Absatz.</p>
                <p>Dritter Absatz.</p>
            </div>
        """))

        assert description == 'Das Gesetz des Lebens. Zweiter Absatz.'
    }

    @Test
    void workWithOnlyAnImageHasNoDescription() {
        assert TeifileParser.openingDescription(opus("""
            <div><p><figure><binaryObject encoding="base64">/9j/4AAQ</binaryObject></figure></p></div>
        """)) == null
    }
}
