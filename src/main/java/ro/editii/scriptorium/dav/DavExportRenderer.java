package ro.editii.scriptorium.dav;

import editii.commons.xml.DomTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.w3c.dom.Node;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.tei.TeiRepo;
import ro.editii.scriptorium.xslt.XsltTool;

import javax.xml.transform.Transformer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DavExportRenderer {
    private static final ThreadLocal<Transformer> TEXT = ThreadLocal.withInitial(
            () -> Util.getTransformer("xslt/tei2text.xsl"));
    private static final ThreadLocal<Transformer> JSON = ThreadLocal.withInitial(
            () -> Util.getTransformer("xslt/xml-to-json.xsl"));
    private static final ThreadLocal<Transformer> XHTML = ThreadLocal.withInitial(
            () -> Util.getTransformer("xslt/teidiv2html.xsl"));

    private final TeiRepo teiRepo;

    public byte[] render(TeiDiv div, DavExportFormat format, String requestUri) {
        div.setTeiRepo(teiRepo);
        final Node node = DomTool.deepCopy(div.getNode());
        final String value = switch (format) {
            // a document: every block on its own line (tei2text.xsl)
            case TXT -> XsltTool.apply(TEXT.get(), node, Map.of("document", "true"));
            case JSON -> XsltTool.apply(JSON.get(), node, Map.of("use-rabbitfish", "true"));
            case XML -> DomTool.serialize(node);
            case XHTML -> asXhtmlDocument(div, node, requestUri);
        };
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String asXhtmlDocument(TeiDiv div, Node node, String requestUri) {
        final String authors = div.getTeiFile().getAuthors().stream()
                .map(it -> it.getVisualName())
                .collect(Collectors.joining(", "));
        final String fragment = XsltTool.apply(XHTML.get(), node, Map.of(
                "requestURI", requestUri,
                "author", authors,
                "relativeRoot", ""));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"UTF-8\"/>"
                + "<title>" + xmlEscape(div.getVisualLabel()) + "</title></head><body>"
                + fragment + "</body></html>";
    }

    private String xmlEscape(String value) {
        if (value == null)
            return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
