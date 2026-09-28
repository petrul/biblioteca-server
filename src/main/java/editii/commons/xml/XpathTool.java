package editii.commons.xml;

import lombok.Getter;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import ro.editii.scriptorium.Util;

import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * a wrapper for org.w3c.dom.Node really
 */
public class XpathTool {

    final private static NamespaceContext namespaceContext = new TeiNamespaceResolver();
    public static final String DIV = Util.DIV;

    @Getter
    Node root;

    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    /**
     *
     * @param is data source
     * @param systemId this is used for resolving relative references for example xi:includes
     */
    public XpathTool(InputStream is, String systemId) {
        this.factory.setNamespaceAware(true);
        this.factory.setXIncludeAware(true);

        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
        try {
            this.root = builder.parse(is, systemId);
        } catch (SAXException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public XpathTool(File file) throws FileNotFoundException {
        this(new BufferedInputStream(new FileInputStream(file)), file.toURI().toASCIIString());
    }

    public XpathTool(String s) {
        this(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)), "" + s.hashCode());
    }


    public XpathTool(Node node) {
        this(node, false);
    }

    public XpathTool(Node node, boolean deepCopyNodes) {
        if (!deepCopyNodes) {
            this.root = node; // because it used to work
        } else {
            try {
                Document doc = this.factory.newDocumentBuilder().newDocument();
                Node n = doc.importNode(node, true);
                doc.appendChild(n);
                this.root = doc;
            } catch (ParserConfigurationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public String xpath(String xpath_str) {
        return this.applyXpathForString(xpath_str);
    }

    public List<Node> xpath_jl(String xpath_str) {
        return this.applyXpathForJavaList(xpath_str);
    }

    public NodeList xpath_ls(String xpath) {
        return this.applyXpathForNodeSet(xpath);
    }


    /**
     * note : we do not use XPathConstants.STRing because that only bring the first text element
     * (@see editii.commons.xml.XPathToolTest#testXpathForString())
     */
    public String applyXpathForString(String str_xpath) {
        try {
            NodeList nodeList = (NodeList) this._applyXpath(str_xpath, XPathConstants.NODESET);
            if (nodeList == null)
                return null;

            return this.nodeListToString(nodeList);
        } catch (XPathExpressionException e) {
            throw new RuntimeException(e);
        }
    }

    protected String nodeListToString(NodeList nodeList) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node crt = nodeList.item(i);
            sb.append(crt.getTextContent());
        }

        return sb.toString();
    }

    public List<Node> applyXpathForJavaList(String str_xpath) {
        final var resp = applyXpathForNodeSet(str_xpath);
        return nodeSet2List(resp);
    }

    public NodeList applyXpathForNodeSet(String str_xpath) {
        try {
            return (NodeList) this._applyXpath(str_xpath, XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            throw new RuntimeException(String.format("for [%s]", str_xpath),
                    e);
        }
    }


    // XPathFactory.newInstance() does JDK service-provider discovery
    // (scanning the classpath's META-INF/services) on every call - cheap
    // once, but this app calls _applyXpath() once per TeiElem, so a full
    // corpus reimport (~167k elements) used to pay that classpath scan
    // roughly 167k times over. Lazily created once per XpathTool instance
    // (one per parsed TEI file - see TeiElem.parseTeiFile's cache_parsedFiles)
    // and reused for every subsequent xpath string against that same file;
    // neither XPath nor XPathExpression is documented thread-safe, but
    // every caller of _applyXpath already goes through this method's own
    // `synchronized` guard, so reuse here introduces no new concurrency risk.
    private XPath xpath;

    // Same reasoning, bigger win: xpath.compile() itself (string parse +
    // expression-tree build) is the expensive part of _applyXpath, and the
    // xpath strings callers pass are a small, endlessly repeated set (the
    // parser asks the same "head"/"text" style queries for every element
    // of every file), so a file's tool instance compiled the same
    // expressions thousands of times over. Cached per instance - correct
    // under the same `synchronized` guard as the XPath above.
    private java.util.Map<String, XPathExpression> compiledXpaths = new java.util.HashMap<>();

    public synchronized Object _applyXpath(String str_xpath, QName qname) throws XPathExpressionException {
        if (this.xpath == null) {
            final XPathFactory xPathfactory = XPathFactory.newInstance();
            this.xpath = xPathfactory.newXPath();
            this.xpath.setNamespaceContext(namespaceContext);
        }
        XPathExpression expr = this.compiledXpaths.get(str_xpath);
        if (expr == null) {
            expr = this.xpath.compile(str_xpath);
            this.compiledXpaths.put(str_xpath, expr);
        }
        final Object result = expr.evaluate(root, qname);
        return result;
    }

    public static Collection<Node> previousSiblings(Node node) {
        Collection<Node> result = new ArrayList<>();
        while (node != null) {
            node = node.getPreviousSibling();

            if (node != null)
                result.add(node);
        }
        return result;
    }

    public static Collection<Node> nextSiblings(Node node) {
        Collection<Node> result = new ArrayList<>();
        while (node != null) {
            node = node.getNextSibling();

            if (node != null)
                result.add(node);
        }
        return result;
    }

    public static synchronized String getXPathRelativeTo(Node node, String beginning) {
        final String wholeXpath = XpathTool.getXPath(node);
        assert wholeXpath.startsWith(beginning);
        final String xpath = wholeXpath.substring(beginning.length());
        return xpath;
    }

    public static synchronized String getXPath(Node node) {
        final Node parent = node.getParentNode();
        if (parent == null) {
            return "";
        }

        final Collection<Node> prevSiblings = previousSiblings(node);
        int nrPreviousDivs = prevSiblings.stream()
            .filter(nd -> DIV.equalsIgnoreCase(nd.getNodeName()))
            .toArray()
            .length;

        final Collection<Node> nextSiblings = nextSiblings(node);
        int nrNextDivs = nextSiblings.stream()
            .filter(nd -> DIV.equalsIgnoreCase(nd.getNodeName()))
            .toArray()
            .length;

        String bracketSelector = "";
        if (nrPreviousDivs > 0 || nrNextDivs > 0)
            bracketSelector = String.format("[%d]", nrPreviousDivs + 1);

        String prefix = namespaceContext.getPrefix(node.getNamespaceURI());
        if (prefix == null)
            prefix = "";
        else
            prefix = prefix + ":";

        return getXPath(parent) + "/" + prefix + node.getNodeName() + bracketSelector;
    }

    public void visit(Function<Node, Object> lambda) {
        this.visit_rec(this.root, lambda);
    }

    public void visit(BiFunction<Node, Object, Object> lambda, Object acc) {
        this.bi_visit_rec(this.root, acc, lambda);
    }

    private void bi_visit_rec(Node node, Object acc, BiFunction<Node, Object, Object> lambda) {
        lambda.apply(node, acc);

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node childNode = children.item(i);
            bi_visit_rec(childNode, acc, lambda);
        }
    }

    public void visit_rec(Node n, Function<Node, Object> lambda) {

        lambda.apply(n);

        NodeList children = n.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node childNode = children.item(i);
            visit_rec(childNode, lambda);
        }
    }

    public String asString() {
        return DomTool.asString(this.root);
    }

    // recursively concat text elements of possibly deep node
    public String getTextContentRec() {
        StringBuilder sb = new StringBuilder();
        this.visit((node, stringBuilder) -> {
            if (node.getNodeType() != Node.TEXT_NODE)
                return null;

            final String txt = node.getTextContent();
            if (txt != null)
                sb.append(txt);
            return null;
        }, sb);
        return sb.toString();
    }

    public static XpathTool from(Node node) {
        return new XpathTool(node);
    }

    public static XpathTool from(Node node, boolean deepCopy) {
        return new XpathTool(node, deepCopy);
    }

    public static XpathTool from(String str) {
        return new XpathTool(str);
    }

    public NodeList select(Predicate<Node> predicate) {
        CustomNodeList acc = new CustomNodeList();
        selectRec(this.root, predicate, acc);
        return acc;
    }

    protected void selectRec(Node root, Predicate<Node> predicate, CustomNodeList acc) {
        if (predicate.test(root)) {
            acc.add(root);
        }

        NodeList children = root.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            selectRec(child, predicate, acc);
        }
    }

    static List<Node> nodeSet2List(NodeList nodeList) {
        final List<Node> resp = new ArrayList<>(nodeList.getLength());
        for (int i = 0; i < nodeList.getLength(); i++) {
            resp.add(nodeList.item(i));
        }
        assert resp.size() == nodeList.getLength();
        return resp;
    }

    public Node xpath_one(String xpath) {
        final NodeList nodeList = this.applyXpathForNodeSet(xpath);
        int listLength = nodeList.getLength();
        if (listLength != 1)
            throw new IllegalStateException(String.format("more than exactly one elem: %d for xpath [%s]", listLength, xpath));
        return nodeList.item(0);
    }
}


class CustomNodeList implements NodeList {

    ArrayList<Node> list = new ArrayList<>(10);

    @Override
    public Node item(int i) {
        return list.get(i);
    }

    @Override
    public int getLength() {
        return this.list.size();
    }

    public void add(Node node) {
        this.list.add(node);
    }

}