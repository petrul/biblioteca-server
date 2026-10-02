package editii.commons.xml;

import org.w3c.dom.Document;
import org.w3c.dom.DOMException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class DomTool {

    private static final String TEI_NAMESPACE = "http://www.tei-c.org/ns/1.0";
    private static final String XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace";
    private static final String XMLNS_NAMESPACE = "http://www.w3.org/2000/xmlns/";

    public static Collection<Node> nodeList2Collection(NodeList nodeList) {
        final ArrayList<Node> arr = new ArrayList<>(nodeList.getLength());
        for (int i = 0; i < nodeList.getLength(); i++) {
            arr.add(nodeList.item(i));
        }
        return arr;
    }

    public static void serialize(Node node, OutputStream os) {

        Transformer transformer;
        try {
            // Saxon explicitly, not TransformerFactory.newInstance(): that
            // only finds Saxon via Saxon-HE's META-INF/services entry, the
            // same classpath-discovery scheme that left _applyXpath() on the
            // JDK's internal Xalan for years. Pinned here like XsltTool does.
            transformer = new net.sf.saxon.TransformerFactoryImpl().newTransformer();
            transformer.transform(new DOMSource(node), new StreamResult(os));
        } catch (TransformerFactoryConfigurationError | TransformerException e) {
            throw new RuntimeException(e);
        }
    }

    public static String serialize(Node node) {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serialize(node, baos);
        try {
            baos.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            return baos.toString("utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static String asString(Node node) {
        return serialize(node);
    }

    // DocumentBuilderFactory.newInstance() does a service-provider
    // classpath scan on every call, and newDocumentBuilder() then sets up
    // a full parser configuration - together they measured as ~11% of the
    // WebITest test JVM's CPU, paid once per deepCopy(), i.e. once per
    // TeiElem.getNode(). The builder is only ever asked for an empty
    // Document here, never a parse, so one builder per thread
    // (DocumentBuilder is stateful and not thread-safe) safely
    // amortizes both costs away.
    private static final ThreadLocal<DocumentBuilder> NEW_DOCUMENT_BUILDER = ThreadLocal.withInitial(() -> {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
    });

    public static Document newDocument() {
        return NEW_DOCUMENT_BUILDER.get().newDocument();
    }

    public static Node deepCopy(Node node) {
        final Document document = newDocument();
        try {
            return document.importNode(node, true);
        } catch (DOMException namespaceError) {
            // Some older imported TEI rows contain prefixed DOM names whose
            // namespace URI was lost during the original parse/import. JDK 25
            // validates that combination more strictly and throws
            // NAMESPACE_ERR here. Rebuild the subtree with valid namespace
            // metadata so Saxon can safely evaluate local-name()/name tests.
            return copyWithSafeNamespaces(node, document);
        }
//        document.importNode(node, true);
//        return document;
    }

    private static Node copyWithSafeNamespaces(Node source, Document target) {
        return switch (source.getNodeType()) {
            case Node.ELEMENT_NODE -> {
                String qualifiedName = safeNodeName(source, "tei:div");
                String prefix = safePrefix(source, qualifiedName);
                String localName = safeLocalName(source, qualifiedName);
                if (localName == null || localName.isBlank()) {
                    int colon = qualifiedName == null ? -1 : qualifiedName.indexOf(':');
                    localName = colon >= 0 ? qualifiedName.substring(colon + 1) : qualifiedName;
                }
                String namespace = safeNamespace(source);
                if (namespace == null && "tei".equals(prefix)) namespace = TEI_NAMESPACE;
                if (namespace == null && "xml".equals(prefix)) namespace = XML_NAMESPACE;
                Element copy = namespace == null
                        ? target.createElementNS("", localName)
                        : target.createElementNS(namespace, qualifiedName);
                if (source.hasAttributes()) {
                    for (int i = 0; i < source.getAttributes().getLength(); i++) {
                        Node attr = source.getAttributes().item(i);
                        String attrName = safeNodeName(attr, null);
                        if (attrName == null) continue;
                        String attrPrefix = safePrefix(attr, attrName);
                        String attrNs = safeNamespace(attr);
                        if (attrNs == null && "xml".equals(attrPrefix)) attrNs = XML_NAMESPACE;
                        if (attrNs == null && ("xmlns".equals(attrName) || "xmlns".equals(attrPrefix))) attrNs = XMLNS_NAMESPACE;
                        if (attrNs == null) copy.setAttribute(attrName, attr.getNodeValue());
                        else copy.setAttributeNS(attrNs, attrName, attr.getNodeValue());
                    }
                }
                for (Node child = source.getFirstChild(); child != null; child = child.getNextSibling()) {
                    copy.appendChild(copyWithSafeNamespaces(child, target));
                }
                yield copy;
            }
            case Node.TEXT_NODE -> target.createTextNode(source.getNodeValue() == null ? "" : source.getNodeValue());
            case Node.CDATA_SECTION_NODE -> target.createCDATASection(source.getNodeValue() == null ? "" : source.getNodeValue());
            case Node.COMMENT_NODE -> target.createComment(source.getNodeValue() == null ? "" : source.getNodeValue());
            case Node.PROCESSING_INSTRUCTION_NODE -> target.createProcessingInstruction(source.getNodeName(), source.getNodeValue());
            default -> target.importNode(source, true);
        };
    }

    private static String safeNodeName(Node node, String fallback) {
        try {
            String name = node.getNodeName();
            return name == null || name.isBlank() ? fallback : name;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String safePrefix(Node node, String qualifiedName) {
        try {
            String prefix = node.getPrefix();
            if (prefix != null) return prefix;
        } catch (RuntimeException ignored) {
            // Fall back to the qualified name below.
        }
        int colon = qualifiedName == null ? -1 : qualifiedName.indexOf(':');
        return colon >= 0 ? qualifiedName.substring(0, colon) : null;
    }

    private static String safeLocalName(Node node, String qualifiedName) {
        try {
            String local = node.getLocalName();
            if (local != null && !local.isBlank()) return local;
        } catch (RuntimeException ignored) {
            // Fall back to the qualified name below.
        }
        int colon = qualifiedName == null ? -1 : qualifiedName.indexOf(':');
        return colon >= 0 ? qualifiedName.substring(colon + 1) : qualifiedName;
    }

    private static String safeNamespace(Node node) {
        try {
            return node.getNamespaceURI();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static Node rootForResults (NodeList nodeList) {
        if (nodeList.getLength() < 2)
            return nodeList.item(0);

        Document newXmlDocument;
        try {
            newXmlDocument = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
        Element root = newXmlDocument.createElement("results");
        newXmlDocument.appendChild(root);
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            Node imported = newXmlDocument.importNode(node, true);
            root.appendChild(imported);
        }

        return root;
    }

    public static void removeSubnodeByNodename(Node node, String nodeName) {
        removeSubnodesByNodenames(node, new String[] { nodeName });
    }

    /**
     * remove all descendent nodes whose names are in the nodeNames list
     * @param nodeNames list of names of nodes to remove
     */
    public static void removeSubnodesByNodenames(Node node, String[] nodeNames) {
        Collection<Node> children = DomTool.nodeList2Collection(node.getChildNodes());
        final List<String> strings = Arrays.asList(nodeNames);
        List<Node> namedChildren = children.stream()
                .filter(it -> strings.contains(it.getNodeName()))
                .collect(Collectors.toList());

        namedChildren.stream().forEach(it -> {
            node.removeChild(it);
        });

        children = DomTool.nodeList2Collection(node.getChildNodes()); // again, because some might have been removed
        children.stream().forEach(it -> removeSubnodesByNodenames(it, nodeNames));// recurse
    }
}
