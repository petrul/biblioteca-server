package ro.editii.scriptorium.service;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.w3c.dom.Node;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.xslt.XsltTool;

import javax.xml.transform.Transformer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * extra-layer between {@link org.springframework.stereotype.Controller}
 * and {@link Service}s which can regroup common
 * code called from REST and web @{@link org.springframework.stereotype.Controller}s
 */
@Service @RequiredArgsConstructor
public class ControllerTool {

    final DivService divService;

    protected static final String XSLT_TEI_2_TEXT_XSL = "xslt/tei2text.xsl";
    static ThreadLocal<Transformer> TRANSFORMERS_TEIDIV2TXT = ThreadLocal.withInitial(() -> Util.getTransformer(XSLT_TEI_2_TEXT_XSL));

    protected Transformer getTei2TxtTransformer() { return TRANSFORMERS_TEIDIV2TXT.get(); }


    public void teiElemToText(ElemInfo elemInfo, HttpServletResponse response) throws IOException {
        this.teiElemToText(elemInfo, response, null);
    }

    /**
     * @param maxDepth null keeps every sub-chapter no matter how deep (the
     *                 default); see {@link Util#pruneDivsBeyondDepth}
     */
    public void teiElemToText(ElemInfo elemInfo, HttpServletResponse response, Integer maxDepth) throws IOException {

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.addHeader(HttpHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE + "; charset=utf-8");

        this.teiElemToDocumentText(elemInfo, response.getOutputStream(), maxDepth);
    }

    /**
     * A division as a readable text document (the .txt export, MCP): every
     * block on its own line - tei2text.xsl's document mode. A paragraph-level
     * element is rendered exactly as {@link #teiElemToString}. Not for
     * paragraph text that is indexed, quoted or hashed: that is
     * {@link #teiElemToString}, whose output must stay the same.
     */
    public void teiElemToDocumentText(ElemInfo elemInfo, OutputStream outputStream, Integer maxDepth) throws IOException {
        this.teiElemToText(elemInfo, outputStream, maxDepth, true);
    }

    public void teiElemToTextInOutputStream(ElemInfo elemInfo, OutputStream outputStream) throws IOException {
        this.teiElemToTextInOutputStream(elemInfo, outputStream, null);
    }

    /**
     * @param maxDepth null keeps every sub-chapter no matter how deep (the
     *                 default, and a no-op for the leaf elements every other
     *                 caller of this method passes - grep/lucene indexing,
     *                 FragmentResolutionService quotes, the /paras endpoint);
     *                 see {@link Util#pruneDivsBeyondDepth}
     */
    public void teiElemToTextInOutputStream(ElemInfo elemInfo, OutputStream outputStream, Integer maxDepth) throws IOException {
        this.teiElemToText(elemInfo, outputStream, maxDepth, false);
    }

    private void teiElemToText(ElemInfo elemInfo, OutputStream outputStream, Integer maxDepth, boolean document) throws IOException {
        Util.pruneDivsBeyondDepth(elemInfo, maxDepth);

        final Node selectedDiv = elemInfo.getNodeCopy();
        // A single paragraph's export stays its indexed text, which quote
        // fragments refer to; only a division is laid out as a document.
        final String name = selectedDiv.getLocalName() != null ? selectedDiv.getLocalName() : selectedDiv.getNodeName();
        document = document && (name.equals("div") || name.endsWith(":div"));

        // tei2text.xsl has no template matching tei:div, so the XSLT built-in
        // rule just recurses into whatever survived pruning above, rendering
        // nested sub-chapters' heads/paragraphs/etc in reading order.
        final var xslt2txt = this.getTei2TxtTransformer();
        // Always passed: the per-thread transformer keeps parameters set by
        // an earlier call.
        XsltTool.apply(xslt2txt,
                selectedDiv,
                outputStream,
                Map.of("document", String.valueOf(document)));
    }

    public String teiElemToString(ElemInfo elemInfo)  {
        try {
            final var baos = new ByteArrayOutputStream();
            this.teiElemToTextInOutputStream(elemInfo, baos);
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
