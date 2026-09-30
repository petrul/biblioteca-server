package editii.commons.xml;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ro.editii.scriptorium.Util;

import java.io.InputStream;

public class TeiDocument extends XpathTool {
    public final static String XPATH_BODY = "/tei:TEI/tei:text/tei:body";
    public TeiDocument(InputStream is, String systemId) {
        super(is, systemId);
    }

    public TeiDocument(Node node) {
        super(node);
    }

    public byte[] getBinaryObject(String id) {
        assert id != null;
        // .// (relative descendant search), not // : this tool's root is
        // usually a detached deep-copied div node (see DivService.getBinaryObject)
        // whose owner Document is empty - an absolute // path resolves against
        // that empty document and finds nothing under a spec-correct engine
        // (Saxon). Xalan only made // work by rooting its DTM at the node.
        final String xpath = String.format(".//tei:binaryObject[@xml:id='%s']", id);
        final NodeList nodeList = this.applyXpathForNodeSet(xpath);

        if (nodeList.getLength() < 1)
            throw new IllegalArgumentException(String.format("cannot find binary object by id [%s]", id));


        final String str = this.nodeListToString(nodeList).trim();

        final byte[] bytes = Util.base64Decode(str);
        assert bytes != null;
        assert bytes.length > 0;

        return bytes;
    }

    public Node getBody() {
        NodeList nodeList = this.applyXpathForNodeSet(XPATH_BODY);
        if (nodeList.getLength() != 1)
            throw new IllegalStateException(String.format("document has %d bodies", nodeList.getLength()));
        return nodeList.item(0);
    }
}
