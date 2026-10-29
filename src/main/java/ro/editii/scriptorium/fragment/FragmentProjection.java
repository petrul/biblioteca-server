package ro.editii.scriptorium.fragment;

import editii.commons.xml.DomTool;
import org.springframework.web.util.HtmlUtils;
import org.w3c.dom.*;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.xslt.XsltTool;

import javax.xml.transform.Transformer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Exact source-to-text map built by instrumenting the existing TEI text stylesheet. */
public final class FragmentProjection {
    private static final ThreadLocal<Transformer> TRANSFORMER = ThreadLocal.withInitial(() -> {
        var resource = FragmentProjection.class.getClassLoader().getResource("xslt/fragment-projection.xsl");
        try (var stream = resource.openStream()) {
            return XsltTool.getTransformer(stream, resource.toExternalForm());
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    });
    public record Span(int start, int end) {}
    public final Node root;
    public final String text;
    public final String version;
    private final Map<Node, String> ids = new IdentityHashMap<>();
    private final Map<String, Node> nodes = new HashMap<>();
    private final Map<String, Span> spans = new HashMap<>();
    private int nextId;

    public FragmentProjection(Node source) {
        root = source.getNodeType() == Node.DOCUMENT_NODE ? ((Document) source).getDocumentElement() : source;
        if (root.getTextContent().length() > 8 * 1024 * 1024)
            throw new IllegalArgumentException("Fragment source exceeds the 8 MiB limit");
        version = digest("fragment-projection-v1\n" + DomTool.asString(root));
        index(root, "", false);
        String marker = "\uE000" + UUID.randomUUID() + "\uE001";
        String marked = XsltTool.apply(TRANSFORMER.get(), root, Map.of("marker", marker));
        StringBuilder plain = new StringBuilder();
        Map<String, Integer> starts = new HashMap<>();
        int pos = 0, at;
        while ((at = marked.indexOf(marker, pos)) >= 0) {
            plain.append(marked, pos, at);
            int stop = marked.indexOf(';', at + marker.length());
            String token = marked.substring(at + marker.length(), stop);
            String id = token.substring(1);
            if (token.charAt(0) == 'S') starts.put(id, plain.length());
            else spans.put(id, new Span(starts.get(id), plain.length()));
            pos = stop + 1;
        }
        plain.append(marked, pos, marked.length());
        text = plain.toString();
        annotate(root);
    }

    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void index(Node node, String path, boolean preserve) {
        if (!(node instanceof Element element)) return;
        String id = "e" + nextId++;
        if (nextId > 100000) throw new IllegalArgumentException("Fragment source has too many nodes");
        ids.put(node, id); nodes.put(id, node);
        element.setAttribute("data-projection-id", id);
        element.setAttribute("data-source-path", path);
        String space = element.getAttributeNS("http://www.w3.org/XML/1998/namespace", "space");
        boolean keep = "preserve".equals(space) || (space.isEmpty() && preserve);
        int child = 0, textChild = 0;
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                child++;
                index(n, path.isEmpty() ? String.valueOf(child) : path + "." + child, keep);
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                if (!keep && n.getNodeValue().matches("[ \\t\\r\\n]*")) continue;
                String textId = id + "t" + (++textChild);
                ids.put(n, textId); nodes.put(textId, n);
            }
        }
    }

    public Span span(Node node) { return spans.get(ids.get(node)); }

    public Node node(List<Integer> steps) {
        Node current = root;
        for (int step : steps) {
            int index = 0; Node found = null;
            for (Node child = current.getFirstChild(); child != null; child = child.getNextSibling())
                if (child.getNodeType() == Node.ELEMENT_NODE && ++index == step) { found = child; break; }
            if (found == null) throw new IllegalArgumentException("Fragment node does not exist");
            current = found;
        }
        return current;
    }

    public int boundary(FragmentSelector.Point point, boolean end) {
        Span span = span(node(point.steps()));
        if (span == null) throw new IllegalArgumentException("Fragment node has no addressable text");
        int value = point.offset() == null ? (end ? span.end() : span.start()) : span.start() + point.offset();
        if (value < span.start() || value > span.end()) throw new IllegalArgumentException("Text offset outside source node");
        if (value > 0 && value < text.length() && Character.isHighSurrogate(text.charAt(value - 1)) && Character.isLowSurrogate(text.charAt(value)))
            throw new IllegalArgumentException("Text boundary splits a Unicode character");
        return value;
    }

    private void annotate(Node node) {
        if (!(node instanceof Element element)) return;
        Span own = span(node);
        if (own != null) {
            element.setAttribute("data-fragment-from", String.valueOf(own.start()));
            element.setAttribute("data-fragment-to", String.valueOf(own.end()));
        }
        List<String> starts = new ArrayList<>(), ends = new ArrayList<>();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                Span span = span(child);
                starts.add(span == null ? "-1" : String.valueOf(span.start()));
                ends.add(span == null ? "-1" : String.valueOf(span.end()));
            } else annotate(child);
        }
        element.setAttribute("data-fragment-text-from", String.join(" ", starts));
        element.setAttribute("data-fragment-text-to", String.join(" ", ends));
    }

    /** Sanitized semantic HTML, retaining ancestor wrappers when a selection cuts a table/stanza. */
    public String html(int from, int to) { return html(root, from, to, -1, -1); }
    public String contextHtml(int from, int to) { return html(root, 0, text.length(), from, to); }

    private String html(Node node, int from, int to, int markFrom, int markTo) {
        Span span = span(node);
        if (span == null || span.end() <= from || span.start() >= to) return "";
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
            return projectedText(Math.max(from, span.start()), Math.min(to, span.end()), markFrom, markTo);
        if (!(node instanceof Element element)) return "";
        String name = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
        String tag = switch (name) {
            case "p", "table", "tr", "td", "q", "list", "item" -> switch (name) { case "list" -> "ul"; case "item" -> "li"; default -> name; };
            case "row" -> "tr"; case "cell" -> "td"; case "l", "stage", "epigraph" -> "p";
            case "head" -> "h3"; case "hi", "emph" -> "em"; case "quote" -> "blockquote";
            case "div", "lg", "sp" -> "div"; case "lb" -> "br"; default -> "span";
        };
        StringBuilder contents = new StringBuilder();
        int cursor = Math.max(from, span.start());
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Span childSpan = span(child);
            if (childSpan == null || childSpan.end() <= from || childSpan.start() >= to) continue;
            int gapEnd = Math.min(to, childSpan.start());
            if (cursor < gapEnd) contents.append(projectedText(cursor, gapEnd, markFrom, markTo));
            contents.append(html(child, from, to, markFrom, markTo));
            cursor = Math.max(cursor, Math.min(to, childSpan.end()));
        }
        if (cursor < Math.min(to, span.end())) contents.append(projectedText(cursor, Math.min(to, span.end()), markFrom, markTo));
        if (name.equals("lb")) return "<br/>" + contents;
        if (contents.isEmpty()) return "";
        String cls = Set.of("lg", "l", "sp", "speaker", "stage", "note").contains(name) ? " class=\"" + name + "\"" : "";
        return "<" + tag + cls + ">" + contents + "</" + tag + ">";
    }
    private String projectedText(int from, int to, int markFrom, int markTo) {
        int left = Math.max(from, markFrom), right = Math.min(to, markTo);
        if (left < right) return HtmlUtils.htmlEscape(text.substring(from, left)) + "<mark>" +
                HtmlUtils.htmlEscape(text.substring(left, right)) + "</mark>" + HtmlUtils.htmlEscape(text.substring(right, to));
        return HtmlUtils.htmlEscape(text.substring(from, to));
    }

}
