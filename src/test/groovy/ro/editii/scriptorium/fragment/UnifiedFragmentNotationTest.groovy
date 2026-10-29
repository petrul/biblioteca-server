package ro.editii.scriptorium.fragment

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.service.ControllerTool
import ro.editii.scriptorium.service.ElemInfo
import javax.xml.parsers.DocumentBuilderFactory
import static org.junit.jupiter.api.Assertions.*

class UnifiedFragmentNotationTest {
    private static projection(String xml) {
        def factory = DocumentBuilderFactory.newInstance()
        factory.namespaceAware = true
        def root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.bytes)).documentElement
        new FragmentProjection(root)
    }

    @Test void nodesAndTextOffsetsHaveDistinctUnambiguousSyntax() {
        assert FragmentSelector.parse('_1.2.3').toString() == '_1.2.3'
        assert FragmentSelector.parse('_01.02:010..1.2:030').toString() == '_1.2:10..1.2:30'
        assert FragmentSelector.parse('_1..3').toString() == '_1..3'
        assert FragmentSelector.fromLegacy('1.10', '1.30').toString() == '_1:10..1:30'
        assert FragmentSelector.fromLegacy('1.2.3.10', '2.3.2.15').toString() == '_1.2.3:10..2.3.2:15'
    }

    @Test void malformedSelectorsAreRejectedRatherThanInterpretedAsPages() {
        ['', '_', '_0', '_1:2', '_1..', '_1...2', '_1..2..3', '_-1', '_1:-2..1:3',
         '_1/2', '_2147483648', '_1:2147483648..1:2', '_1.2:x..1.2:4'].each { value ->
            assertThrows(IllegalArgumentException) { FragmentSelector.parse(value) }
        }
    }

    @Test void projectionUsesTheActualIndexingStylesheetIncludingNotesAndVerseBreaks() {
        String xml = '''<div xmlns="http://www.tei-c.org/ns/1.0">
          <head>Title</head><p>Some <hi>italic</hi> text<note>the note</note>.</p>
          <lg><l>Verse one.</l><l>Verse two.</l></lg><table><row><cell>A</cell><cell>B</cell></row></table>
        </div>'''
        def factory = DocumentBuilderFactory.newInstance(); factory.namespaceAware = true
        def source = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.bytes)).documentElement
        String expected = new ControllerTool(null).teiElemToString(ElemInfo.builder().nodeCopy(source).build())
        def mapped = projection(xml)
        assert mapped.text == expected
        assert mapped.text.contains(' [the note]\n')
        assert mapped.text.contains('Verse one.\nVerse two.')
    }

    @Test void crossElementSelectionKeepsPartialParagraphAndTableAncestors() {
        def mapped = projection('<div xmlns="http://www.tei-c.org/ns/1.0"><p>Alpha <hi>beta</hi> gamma.</p><table><row><cell>First</cell><cell>Last</cell></row></table></div>')
        def selector = FragmentSelector.parse('_1:6..2.1.2:2')
        int from = mapped.boundary(selector.start(), false), to = mapped.boundary(selector.end(), true)
        assert mapped.text.substring(from, to) == 'beta gamma.\nFirstLa'
        String html = mapped.html(from, to).replace('\n', '')
        assert html.contains('<p><em>beta</em> gamma.</p>')
        assert html.contains('<table><tr><td>First</td><td>La</td></tr></table>')
        assert !html.contains('Alpha')
    }

    @Test void structuredHtmlRetainsTheExactProjectionIncludingGeneratedNoteAndLabelText() {
        def mapped = projection('<div xmlns="http://www.tei-c.org/ns/1.0"><p>A <label>label</label>and <hi>word</hi><note>note text</note>.</p><lg><l>One</l><l>Two</l></lg></div>')
        String html = mapped.html(0, mapped.text.length())
        def factory = DocumentBuilderFactory.newInstance()
        def parsed = factory.newDocumentBuilder().parse(new ByteArrayInputStream(html.bytes))
        assert parsed.documentElement.textContent == mapped.text
        assert html.contains(' [note text]') || html.contains(' [') && html.contains('note text')
    }

    @Test void wholeStanzasAndIndividualVerseNodesRemainAddressable() {
        def mapped = projection('<div xmlns="http://www.tei-c.org/ns/1.0"><lg><l>First.</l><l>Second.</l></lg></div>')
        def stanza = FragmentSelector.parse('_1'), verse = FragmentSelector.parse('_1.2')
        assert mapped.html(mapped.boundary(stanza.start(), false), mapped.boundary(stanza.start(), true)).contains('class="lg"')
        assert mapped.text.substring(mapped.boundary(verse.start(), false), mapped.boundary(verse.start(), true)) == 'Second.'
    }

    @Test void utf16OffsetsMustNotSplitAnEmojiOrEscapeTheirNode() {
        def mapped = projection('<div xmlns="http://www.tei-c.org/ns/1.0"><p>A 😀 word.</p></div>')
        def selector = FragmentSelector.parse('_1:2..1:4')
        assert mapped.text.substring(mapped.boundary(selector.start(), false), mapped.boundary(selector.end(), true)) == '😀'
        assertThrows(IllegalArgumentException) { mapped.boundary(FragmentSelector.parse('_1:3..1:4').start(), false) }
        assertThrows(IllegalArgumentException) { mapped.boundary(FragmentSelector.parse('_1:0..1:99').end(), true) }
        assertThrows(IllegalArgumentException) { mapped.boundary(FragmentSelector.parse('_2').start(), false) }
    }
}
