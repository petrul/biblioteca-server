package ro.editii.scriptorium

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.xslt.XsltTool

import static org.junit.jupiter.api.Assertions.*

// The tei->html stylesheet served to the reader (xslt/teidiv2html.xsl):
// the drama elements produced by the scriptorium-masters post hook
// must render as readable HTML - sp blocks (with the who pointer, for
// character-level features), speaker spans, stage paragraphs.
class Teidiv2HtmlDramaTest {

    private String render(String xml) {
        final xsl = getClass().getClassLoader().getResourceAsStream("xslt/teidiv2html.xsl")
        final tr = XsltTool.getTransformer(xsl, "xslt/teidiv2html.xsl")
        XsltTool.apply(tr, xml)
    }

    @Test
    void speechWithSpeakerAndWho() {
        final html = render("""<div xmlns="http://www.tei-c.org/ns/1.0" type="div3">
             <head>Scena prima</head>
             <sp who="#MIRANDOLINA"><speaker>MIRANDOLINA</speaker><p>M'inchino a questi cavalieri.</p></sp>
            </div>""")
        assertTrue html.contains('<div class="sp" data-who="MIRANDOLINA">'),
                    'the sp block carries the who pointer as data-who'
        assertTrue html.contains('<span class="speaker">MIRANDOLINA</span>'),
                    'the printed speaker line is a span'
        assertTrue html.contains("<p>M'inchino a questi cavalieri.</p>"),
                    'the speech paragraph stays intact'
    }

    @Test
    void speakerAnnotationRidesAlong() {
        // the hook keeps the printed annotation inside <speaker>
        // ("MARCHESE (ironico)") - the stylesheet must not strip it
        final html = render("""<div xmlns="http://www.tei-c.org/ns/1.0" type="div3">
             <sp who="#MARCHESE"><speaker>MARCHESE (ironico)</speaker><p>Voi credete di soverchiarmi.</p></sp>
            </div>""")
        assertTrue html.contains('<span class="speaker">MARCHESE (ironico)</span>')
    }

    @Test
    void stageDirectionsAreTheirOwnParagraph() {
        final html = render("""<div xmlns="http://www.tei-c.org/ns/1.0" type="div3">
             <stage>Il Marchese ed il Conte.</stage>
            </div>""")
        assertTrue html.contains('<p class="stage">Il Marchese ed il Conte.</p>')
    }

    @Test
    void speechWithoutWhoStillRenders() {
        // books not touched by the hook may carry bare <sp> - graceful
        final html = render("""<div xmlns="http://www.tei-c.org/ns/1.0" type="div3">
             <sp><speaker>YAGO.</speaker><p>Y podeis creerlo.</p></sp>
            </div>""")
        assertTrue html.contains('<div class="sp">')
        assertFalse html.contains('data-who')
        assertTrue html.contains('<span class="speaker">YAGO.</span>')
    }
}
