package ro.editii.scriptorium.tei;

import editii.commons.xml.DomTool;
import editii.commons.xml.XpathTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ro.editii.scriptorium.TextbaseConfig;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.Languages;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * TOC = table of contents.
 * Basically parses a tree of divs of a TEI and inserts results into db entities.
 */
@Log4j2
@RequiredArgsConstructor
@Component
public class TeifileParser {

    public static final String DIV = Util.DIV;

    // getXPath's bracket now correctly reflects real sibling structure
    // (see editii.commons.xml.XpathTool's own fix comment): <text> always
    // has exactly one preceding element sibling in a valid TEI document -
    // <teiHeader> is mandatory and always comes first - so it always gets
    // bracketed too, deterministically. <body> itself stays bracket-free:
    // nothing in this corpus uses TEI's optional <front>/<back> siblings
    // to <body> within <text>. Must stay in sync with
    // TeiElem.TEI_TEXT_BODY, which reconstructs the same prefix in the
    // other direction (relative stored path -> full xpath).
    public static final String TEI_BODY_XPATH_PREFIX = "/tei:TEI/tei:text[1]/tei:body";

    final TeiFileRepository teiFileRepository;
    final AuthorRepository authorRepository;
    final TeiDivRepository teiDivRepository;

    final TextbaseEventsPublisher textbaseEventsPublisher;
    final TextbaseConfig textbaseConfig;

    final private AuthorStrIdComputer authorStrIdComputer;

    protected Map<Node, TeiDiv> node2div = new LinkedHashMap<>();

    private static String xpath(XpathTool xpathTool, String str_xpath) {
        return xpathTool.xpath(str_xpath);
    }

    private static Collection<Node> xpath2Nodes(XpathTool xpathTool, String strXpath) {
        return DomTool.nodeList2Collection(xpathTool.applyXpathForNodeSet(strXpath));
    }

    public List<TeiDiv> parse(String content) throws TeiFileAlreadyImportedException {
        return this.parse(content, null);
    }

    public List<TeiDiv> parse(String content, Languages langHint) throws TeiFileAlreadyImportedException {
        final String id = Util.sha256Hex(content);
        return this.parse(id, content, langHint);
    }

    public List<TeiDiv> parse(String name, String content, Languages langHint) throws TeiFileAlreadyImportedException {
        final ByteArrayInputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        try(is) {
            return this.parse(name,
                    is,
                    langHint
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param name key that identifies the content; i.e. the unique filename, will be recorded in the database as the source where the parsed xml comes from
     *             <p>
     *             the Author is reused if existing.
     *             the TeiFile, it depends on the forceReimport flag :
     *             - if true, a deletion of the TeiFile and all sousjacent data
     *             - if false, a checked exception should be thrown
     *
     * @return warn, if a TEI <div> does not contain a <head> it will be ignored.
     */
    public List<TeiDiv> parse(String name, InputStream is, Languages langHint) throws TeiFileAlreadyImportedException {

        final List<TeiDiv> resp = new ArrayList<>();

        this.node2div.clear();
        final var xpathTool = new XpathTool(is, name);

        final StopWatch watch = new StopWatch(); watch.start();

        // this should be smth like 'Alecsandri,Vasile'
        final String authorName = xpath(xpathTool, "/tei:TEI/tei:teiHeader//tei:titleStmt/tei:author");

        Author author = Author.newFromOriginalNameInTeiFile(authorName);

        // check existing author already in db
        final Optional<Author> authorOptionalRetrieved = this.authorRepository.getByOriginalNameInTeiFile(author.getOriginalNameInTeiFile());
        if (authorOptionalRetrieved.isPresent()) {
             // already an originalname present in db
            author = authorOptionalRetrieved.get();
        } else {
            // no such original name in db
            this.authorStrIdComputer.compute_strid_for_new_author(author);
            this.authorRepository.save(author);
        }

        // teiFile
        final String teiFilename = name;
        final TeiFile teifile = new TeiFile();

        teifile.setFilename(teiFilename);
        final String title = xpath(xpathTool, "/tei:TEI/tei:teiHeader//tei:titleStmt/tei:title/text()").trim();
        teifile.setTitle(StringUtils.truncate(title, 1000));
        teifile.setAuthors(new ArrayList<>(Arrays.asList(author)));
        // langHint is now the detected document language (see
        // TeiFileDbService.importTeiFile / LanguageDetectionService), not a
        // directory-path guess - recorded once here at the file level,
        // same value TeiDiv.lang gets per-div below (parcurge_rec).
        teifile.setLanguage(langHint);

        // check if already existing teiFile
        final Optional<TeiFile> optionalTeiFile = this.teiFileRepository.getByFilename(teiFilename);

        if (optionalTeiFile.isPresent())
            throw new TeiFileAlreadyImportedException("teifile " + teiFilename + " already imported");

        this.teiFileRepository.save(teifile);

        final Node body = xpathTool.xpath_one("//tei:text/tei:body");
        final NodeList bodyChildren = body.getChildNodes();

        this.node2div = new LinkedHashMap<>(); // reinit

        int opusCounter = 0;

        for (int i = 0; i < bodyChildren.getLength(); i++) {
            final Node node = bodyChildren.item(i);
            if (isDivNode(node)) {

                opusCounter++;

                final List<TeiDiv> acc = new ArrayList<>(1000);
                final List<TeiDiv> importedOpuses = new ArrayList<>();
                parcurge_rec(node, null, i, teifile, acc, resp, importedOpuses, langHint);

                if (acc.size() > 0) {
                    this.teiDivRepository.saveAll(acc);
                    acc.clear();
                }
                signalEventNewOpuses(importedOpuses);
            }
        }

        watch.stop();
        log.info("done parsing {}, {} root divs, took {}", name, opusCounter, watch);
        return resp;
    }

    private static boolean isDivNode(Node node) {
        return node.getNodeType() == Node.ELEMENT_NODE &&
                DIV.equalsIgnoreCase(node.getNodeName());
    }

    private void signalEventNewOpuses(List<TeiDiv> acc) {
        acc.stream()
                .filter(it  -> it.isOpus())
                .forEach(it -> this.textbaseEventsPublisher.signalNewOpusImported(
                        TeiDivDto.fromTeiDiv(it,
                        this.textbaseConfig.getTextbaseAdvertisedUrl())));
    }

    protected String compute_unique_head_url_fragment(TeiDiv div) {

        final TeiDiv parentDiv = (TeiDiv) div.getParent();

        final CandidateUrlFragmGeneratorForTeiDivHead iterable = new CandidateUrlFragmGeneratorForTeiDivHead(div.getHead());
        for (String candidate : iterable) {

            // check for the case where another edition of the same work, already exists imported into the db
            if (parentDiv == null) {
                Author author = div.getTeiFile().getAuthor();
                List<TeiDiv> opuses = this.teiDivRepository.findOperaForAuthorStrId(author.getStrId());
                if (opuses
                        .stream()
                        .anyMatch( it -> candidate.equals(it.getUrlFragment()))) {
                    // here we found another opus already imported, of the same author bearing the same name, probably another edition.
                    continue; // next candidate
                }
            }

            // if no other child has the same urlFragment, then return it as good
            if (parentDiv == null
                    || parentDiv.getDbChildren() == null
                    || parentDiv.getDbChildren()
                    .stream()
                    .noneMatch( it -> candidate.equals(it.getUrlFragment())))
                return candidate;
        }
        throw new IllegalStateException("should never get here, iterator is infinite");
    }

    /**
     * @param nth indicates that node is parent's nth child (starting with 0, because the java DOM gets the childNodes from 0)
     */
    private void parcurge_rec(final Node node, Node parent, int nth, final TeiFile teiFile,
                              List<TeiDiv> acc, List<TeiDiv> importedDivs,
                              List<TeiDiv> importedOpuses, final Languages langHint) {

        final TeiDiv parentDiv;
        if (parent == null)
            parentDiv = null;
        else
            parentDiv = this.node2div.get(parent);

        assert DIV.equalsIgnoreCase(node.getNodeName());

        final String head = Util.maxNCharsOf(this.getHead(node), TeiDiv.MAX_HEAD_SIZE);

        if (isMarkedWithX(head))
            return;

        if (head == null || head.isEmpty()) {
            // ignore this div
            // divs with empty head are generated by the odttotei when you have a h3 under a h1 for example.

        } else {
            // normal div

            final TeiDiv div = new TeiDiv();

            div.setHead(head);
            div.setParent(parentDiv);
            div.setTeiFile(teiFile);
            div.setLang(langHint);

            final String urlFragm = this.compute_unique_head_url_fragment(div);
            div.setUrlFragment(urlFragm);

            final String xpath = XpathTool.getXPathRelativeTo(node, TEI_BODY_XPATH_PREFIX);
            div.setXpath(xpath);

            if (parentDiv != null)
                parentDiv.addChild(div);

            final String textContent = new XpathTool(node).getTextContentRec();
            final String trimmed = textContent
                    .replaceAll("\\s+", " ")
                    .trim();
            final int size = trimmed.length();
            final int wordSize = trimmed.split("\\s+").length;
            div.setSize(size);
            div.setWordSize(wordSize);
            div.setNth(nth + 1); // because we store xpath-style, which starts at 1, not at 0

            this.node2div.put(node, div);

            if (acc.size() >= 1000) {
                this.teiDivRepository.saveAll(acc); // use saveAll so inserts be batch'd
                acc.clear();
            }
            acc.add(div);
            importedDivs.add(div);
            if (div.isOpus())
                importedOpuses.add(div);

            log.debug(div.toString());

            parent = node;

        }

        // recurse to children
        final NodeList childNodes = node.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            final Node child = childNodes.item(i);
            if (isDivNode(child)) {
                parcurge_rec(child, parent, i, teiFile, acc, importedDivs, importedOpuses, langHint);
            }
        }
    }

    /**
     * @return true if node is comment or text whitespace
     */
    private boolean commentOrWhitespace(Node node) {
            return node.getNodeType() == Node.COMMENT_NODE
                    ||
                    (node.getNodeType() == Node.TEXT_NODE
                                && node.getTextContent().isBlank());
    }

    public static void replaceLbWithBlank(Node node, int level) {
        final NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node childNode = children.item(i);
            if (childNode.getNodeName().equals("lb")) {
                final Node whitespace = node.getOwnerDocument().createTextNode(" ");
                childNode.getParentNode().replaceChild(whitespace, childNode);
            }

            replaceLbWithBlank(childNode, level + 1);
        }
    }


    protected String getHead(Node div) {
        final XpathTool divXpathTool = new XpathTool(div, true);
        final NodeList headSet = divXpathTool.applyXpathForNodeSet("/tei:div/tei:head");
        if (headSet.getLength() < 1)
            return null;
        final List<String> headBuilder = new ArrayList<>(1); // most only have one head

        // there should only be one head, but make it work even if there are several
        for (int h = 0; h < headSet.getLength(); h++) {
            Node headNode = headSet.item(h);
            headNode = DomTool.deepCopy(headNode);

            // TODO do this on head; right now the next two lines are applied to the whole div
            replaceLbWithBlank(headNode, 0);

            // strange things that you might occasionally find in a <head>
            DomTool.removeSubnodesByNodenames(headNode, new String[] {"label", "note", "figure", "binaryObject"});

            StringBuilder sb = new StringBuilder();
            /* because there is an apparent bug in the java dom :
             * XPathTool.applyXpathForNodeSet(".//text()")
             * after removeChild, so use the following workaround
             */
            NodeList nodeSet = XpathTool.from(headNode, true).select(it -> it.getNodeType() == Node.TEXT_NODE);
            for (int i = 0; i < nodeSet.getLength(); i++) {
                Node crt = nodeSet.item(i);
                final String crtText = crt.getTextContent();
                final String whitespaceRemoved = this.removeWhitespace(crtText);
                if (whitespaceRemoved.isEmpty() && crtText.contains("\n")) {
                    // interstitial whitespace between tags, just ignore them
                } else {
                    sb.append(crtText);
                }
            }

            String head = this.nbspToSpace(sb.toString())
                    .replaceAll("\\n", "")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (head != null && !head.isEmpty())
                headBuilder.add(head);

        }

        return String.join(" ", headBuilder);
    }

    // replace &nbsp; with regular trimmable space
    String nbspToSpace(String s) {
        return s.replaceAll("\u00A0", " ");
    }

    String removeWhitespace(String s) {
        return s.replaceAll("\\n", "")
                .replaceAll("\\s+", "");
    }


    /**
     * @return true if head contins something like /x/ or [x], case ignored
     */
    protected static boolean isMarkedWithX(String head) {
        if (head == null)
            return false;
        return head.matches("(?i)^.*?[\\[|/]x+[\\]|/].*$");
    }

}
