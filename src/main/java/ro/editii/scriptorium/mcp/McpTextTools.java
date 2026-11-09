package ro.editii.scriptorium.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ro.editii.scriptorium.service.ControllerTool;
import ro.editii.scriptorium.service.DivService;
import ro.editii.scriptorium.service.ElemInfo;
import ro.editii.scriptorium.fragment.FragmentResolutionService;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.xslt.XsltTool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.w3c.dom.Node;
import editii.commons.xml.DomTool;

/**
 * MCP tools for reading the corpus texts themselves: a division's content in
 * plain text, TEI XML or a JSON AST (the same rendering the public
 * /{author}/{opus}/... pages serve), and quote fragments resolved exactly
 * like the /quote pages. Read-only: rendering goes through the same read
 * paths the REST API uses.
 */
@Service
public class McpTextTools {

    /** Same XSLT the pages use to serve .json (DivController's json transformer). */
    static final String XSLT_XML_TO_JSON = "xslt/xml-to-json.xsl";

    private final ObjectProvider<DivService> divService;
    private final ObjectProvider<ControllerTool> controllerTool;
    private final ObjectProvider<FragmentResolutionService> fragments;

    public McpTextTools(ObjectProvider<DivService> divService,
            ObjectProvider<ControllerTool> controllerTool,
            ObjectProvider<FragmentResolutionService> fragments) {
        this.divService = divService;
        this.controllerTool = controllerTool;
        this.fragments = fragments;
    }

    /** Rendered formats of one div, mirroring DivController's .txt/.xml/.json content negotiation. */
    public enum TextFormat { txt, xml, json }

    @Tool(name = "read_text", description =
            "Read a corpus division as plain text (format=txt), raw TEI XML (format=xml) or a JSON " +
            "AST (format=json) - the same rendering the /.txt, /.xml and /.json page variants serve. " +
            "'path' is the stable corpus path, e.g. 'shakespeare/hamlet/act_iv/another_room_in_the_castle'.")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public String readText(
            @ToolParam(description = "Stable corpus path of the div to read") String path,
            @ToolParam(required = false, description = "Rendered format: txt (default), xml or json") TextFormat format,
            @ToolParam(required = false, description = "How many levels of sub-chapters to include " +
                    "below the div (omitted: all, like the pages' depth parameter)") Integer depth) {

        final ElemInfo elemInfo = this.divService.getObject().findElemInfoByPath(path)
                .orElseThrow(() -> new IllegalArgumentException("no corpus element at " + path));
        final Node node = elemInfo.getNodeCopy();
        final TextFormat chosen = format == null ? TextFormat.txt : format;

        return switch (chosen) {
            // The plain-text renderer prunes by depth itself (see ControllerTool).
            case txt -> {
                try {
                    final var out = new ByteArrayOutputStream();
                    this.controllerTool.getObject().teiElemToTextInOutputStream(elemInfo, out, depth);
                    yield out.toString(StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("text rendering failed", e);
                }
            }
            case xml -> {
                pruneDepth(elemInfo, depth);
                yield DomTool.serialize(node);
            }
            case json -> {
                pruneDepth(elemInfo, depth);
                yield XsltTool.apply(jsonTransformer(), node, Map.of("use-rabbitfish", "true"));
            }
        };
    }

    @Tool(name = "quote_fragment", description =
            "A quotation inside one chapter as plain paragraphs: resolves the start and end dot " +
            "paths exactly like the /quote pages (any paragraph and character range inside them). " +
            "E.g. start='2:93', end='2:403' means from paragraph 2, char 93, to paragraph 2, char 403.")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public Map<String, Object> quoteFragment(
            @ToolParam(description = "Stable path of the div/chapter the quote lives in") String divPath,
            @ToolParam(description = "Quote start as a dot path such as '20' (whole paragraph 20) " +
                    "or '2:93' (paragraph 2, char 93)") String start,
            @ToolParam(description = "Quote end in the same dot path form") String end) {

        final TeiElem elem;
        try {
            elem = this.divService.getObject().getByPath(divPath);
        } catch (org.springframework.web.server.ResponseStatusException notFound) {
            throw new IllegalArgumentException("no corpus element at " + divPath);
        }
        if (!(elem instanceof TeiDiv div))
            throw new IllegalArgumentException("a quote's path must resolve to a div/chapter, not " + divPath);

        final var fragment = this.fragments.getObject().resolve(div, start, end);
        return Map.of(
                "sourcePath", div.getCompletePath(),
                "start", start,
                "end", end,
                "paragraphs", fragment.getParagraphs());
    }

    private static void pruneDepth(ElemInfo elemInfo, Integer depth) {
        ro.editii.scriptorium.Util.pruneDivsBeyondDepth(elemInfo, depth);
    }

    /** One transformer per thread, like DivController's ThreadLocal transformers. */
    private static javax.xml.transform.Transformer jsonTransformer() {
        // Reflection-free equivalent of DivController's static ThreadLocal: the
        // XSLT is on the classpath and transformers are not thread-safe.
        return JSON_TRANSFORMER.get();
    }

    private static final ThreadLocal<javax.xml.transform.Transformer> JSON_TRANSFORMER =
            ThreadLocal.withInitial(() -> ro.editii.scriptorium.Util.getTransformer(XSLT_XML_TO_JSON));
}
