package ro.editii.scriptorium.mcp

// Explicit same-package imports: groovyc's incremental compilation sometimes
// leaves same-package classes unresolved here; an explicit import forces
// static resolution at compile time instead of a dynamic property call.
import ro.editii.scriptorium.mcp.McpGraphqlTools
import ro.editii.scriptorium.mcp.McpSearchTools
import ro.editii.scriptorium.mcp.McpTextTools
import ro.editii.scriptorium.mcp.McpTextTools.TextFormat

import groovy.util.ProxyGenerator
import org.springframework.beans.factory.ObjectProvider
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows

import ro.editii.scriptorium.fragment.FragmentResolutionService
import ro.editii.scriptorium.fragment.FragmentText
import ro.editii.scriptorium.mcp.McpTextTools.TextFormat
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiElem
import ro.editii.scriptorium.service.ControllerTool
import ro.editii.scriptorium.service.DivService
import ro.editii.scriptorium.service.ElemInfo
import ro.editii.scriptorium.xslt.XsltTool

/**
 * Native Groovy mocks throughout (per the house test convention): format
 * selection of read_text runs against real in-memory TEI content with the
 * same XSLTs and serializers the public .txt/.xml/.json page variants use;
 * the quote tool's delegation is verified with recorded calls.
 */
class McpTextToolsTest {

    static final String CONTENT_XML = '<div><head>Chapter</head><p>Once upon a library.</p></div>'

    final List calls = []

    final DivService divService = ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass(
            [findElemInfoByPath: { String path -> calls << ['find', path]; Optional.of(elemInfo(CONTENT_XML)) },
             getByPath: { String path -> calls << ['getByPath', path]; divFor(path) }],
            DivService, [null, null, null] as Object[]) as DivService

    final ControllerTool controllerTool = ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass(
            [teiElemToDocumentText: { ElemInfo info, OutputStream out, Integer depth ->
                calls << ['text', depth]; out.write('rendered text'.bytes); null }],
            ControllerTool, [null] as Object[]) as ControllerTool

    final FragmentResolutionService fragments = ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass(
            [resolve: { TeiDiv div, String start, String end ->
                calls << ['fragment', div, start, end]
                new FragmentText(div, start, end, ['quoted text']) }],
            FragmentResolutionService, [null, null] as Object[]) as FragmentResolutionService

    final McpTextTools tools = new McpTextTools(provider(divService), provider(controllerTool), provider(fragments))

    /** Groovy-native supplier mock, coerced from a map so getObject() resolves directly. */
    private static ObjectProvider provider(Object bean) {
        [getObject: { -> bean }, stream: { java.util.stream.Stream.empty() }] as ObjectProvider
    }

    private static ElemInfo elemInfo(String xml) {
        def stream = new ByteArrayInputStream(xml.getBytes('utf-8'))
        ElemInfo.builder().nodeCopy(XsltTool.parseInputStreamToNode(stream)).build()
    }

    private TeiDiv divFor(String path) {
        new TeiDiv() {
            @Override
            String getCompletePath() { path }
        }
    }

    @Test
    void readTextDefaultsToPlainTextAndPassesDepthThrough() {
        assert tools.readText('author/opus/chapter', null, 2) == 'rendered text'
        assert calls*.first() == ['find', 'text']
        // the txt renderer received the requested depth, like the .txt pages
        assert calls[1][1] == 2
    }

    @Test
    void readTextWithoutDepthPassesNullLikeThePages() {
        assert tools.readText('author/opus/chapter', TextFormat.txt, null) == 'rendered text'
        assert calls[1][1] == null
    }

    @Test
    void readTextAsXmlSerializesTheNode() {
        def xml = tools.readText('author/opus/chapter', TextFormat.xml, null)
        assert xml.contains('Once upon a library.')
        assert xml.contains('<div')
    }

    @Test
    void readTextAsJsonRunsTheAstTransformer() {
        def json = tools.readText('author/opus/chapter', TextFormat.json, null)
        assert json.contains('Once upon a library.')
        assert json.trim().startsWith('{')
    }

    @Test
    void unknownPathsAreRejected() {
        def missing = ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass(
                [findElemInfoByPath: { String path -> Optional.empty() }],
                DivService, [null, null, null] as Object[]) as DivService
        def toolsWithMiss = new McpTextTools(provider(missing), provider(controllerTool), provider(fragments))
        assertThrows(IllegalArgumentException) { toolsWithMiss.readText('no/such/path', null, null) }
    }

    @Test
    void quoteFragmentResolvesThroughTheFragmentService() {
        def result = tools.quoteFragment('author/opus/chapter', '2:93', '2:403')
        assert calls*.first() == ['getByPath', 'fragment']
        assert result.sourcePath == 'author/opus/chapter'
        assert result.start == '2:93'
        assert result.end == '2:403'
        assert result.paragraphs == ['quoted text']
    }

    @Test
    void quoteFragmentRejectsNonDivPaths() {
        def elem = ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass(
                [:], TeiElem, [] as Object[]) as TeiElem
        def elemDivService = ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass(
                [getByPath: { String path -> elem }], DivService, [null, null, null] as Object[]) as DivService
        def elemTools = new McpTextTools(provider(elemDivService), provider(controllerTool), provider(fragments))
        assertThrows(IllegalArgumentException) { elemTools.quoteFragment('author/opus/chapter/par', '1', '2') }
    }
}
