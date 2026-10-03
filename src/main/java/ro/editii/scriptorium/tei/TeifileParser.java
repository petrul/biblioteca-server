package ro.editii.scriptorium.tei;

import editii.commons.xml.DomTool;
import editii.commons.xml.XpathTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import ro.editii.scriptorium.TextbaseConfig;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.dao.TeiOpusRepository;
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

    // Kept as an optional setter-injected dependency so the small parser unit
    // tests can continue to construct TeifileParser with their existing
    // fixture repositories.
    private TeiOpusRepository teiOpusRepository;

    private EntityManager entityManager;

    @Autowired(required = false)
    void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Autowired
    void setTeiOpusRepository(TeiOpusRepository repository) {
        this.teiOpusRepository = repository;
    }

    final TextbaseEventsPublisher textbaseEventsPublisher;
    final TextbaseConfig textbaseConfig;

    final private AuthorStrIdComputer authorStrIdComputer;

    protected Map<Node, TeiDiv> node2div = new LinkedHashMap<>();
    private final Map<TeiDiv, String> openingDescriptions = new IdentityHashMap<>();

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

    @Transactional
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
    @Transactional(rollbackFor = TeiFileAlreadyImportedException.class)
    public List<TeiDiv> parse(String name, InputStream is, Languages langHint) throws TeiFileAlreadyImportedException {

        final List<TeiDiv> resp = new ArrayList<>();

        this.node2div.clear();
        this.openingDescriptions.clear();

        // Already-imported verdict BEFORE anything is saved. This is a
        // checked exception and the default @Transactional does not roll
        // back on checked exceptions (the rollbackFor above is the belt
        // to this check's suspenders): an author saved ahead of this
        // point would commit while the import aborts - a file-less
        // orphan author no cleanup can ever reach, since deleteTeiFile
        // only inspects the authors of the file it deletes. More than
        // one importer JVM can share one DB (Globals.IMPORT_TEIS_WORKING
        // is a per-JVM lock), so reaching this check with the file
        // already present is a real race, not a theoretical one.
        if (this.teiFileRepository.getByFilename(name).isPresent())
            throw new TeiFileAlreadyImportedException("teifile " + name + " already imported");

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
            // No exact name match - but the corpus spells the same person
            // differently across files (diacritics, token order,
            // punctuation; see Author.nameIdentityKey). Before creating a
            // second row for them, look for an existing row whose name
            // folds to the same identity key and reuse it. Only a genuine
            // first sighting falls through to the new-author path.
            final String identityKey = Author.nameIdentityKey(author.getOriginalNameInTeiFile());
            final Optional<Author> variant = this.authorRepository.findAll().stream()
                    .filter(existing -> !identityKey.isEmpty()
                            && identityKey.equals(Author.nameIdentityKey(existing.getOriginalNameInTeiFile())))
                    .findFirst();
            if (variant.isPresent()) {
                log.warn("reusing author {} [{}] for name variant [{}] - same identity key \"{}\"",
                        variant.get().getStrId(), variant.get().getOriginalNameInTeiFile(),
                        author.getOriginalNameInTeiFile(), identityKey);
                author = variant.get();
            } else {
                // no such original name in db
                this.authorStrIdComputer.compute_strid_for_new_author(author);
                this.authorRepository.save(author);
            }
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

        this.teiFileRepository.save(teifile);

        final Node body = xpathTool.xpath_one("//tei:text/tei:body");
        final NodeList bodyChildren = body.getChildNodes();

        this.node2div = new LinkedHashMap<>(); // reinit

        int opusCounter = 0;
        final List<TeiDiv> allImportedOpuses = new ArrayList<>();

        for (int i = 0; i < bodyChildren.getLength(); i++) {
            final Node node = bodyChildren.item(i);
            if (isDivNode(node)) {

                opusCounter++;

                final List<TeiDiv> acc = new ArrayList<>(1000);
                final List<TeiDiv> importedOpuses = new ArrayList<>();
                parcurge_rec(node, null, i, teifile, acc, resp, importedOpuses, langHint);

                if (acc.size() > 0) {
                    // TeiOpus has a mandatory one-to-one reference to the
                    // root TeiDiv. Flush the parsed tree before persisting
                    // that envelope so Hibernate never sees a transient
                    // TeiDiv when the parser is invoked outside an enclosing
                    // service transaction (as the integration tests do).
                    flushDivBatch(acc);
                    acc.clear();
                }
                // Do not let parser-created entity instances (or pending
                // self-referencing batch actions) leak into the metadata
                // insert.  The root is reloaded below as a database-backed
                // entity before the TEI_OPUS FK is written.
                if (entityManager != null) {
                    entityManager.flush();
                    entityManager.clear();
                }
                allImportedOpuses.addAll(importedOpuses);
            }
        }

        // TeiOpus is the editable/enriched envelope for root-level divs.  It
        // is deliberately persisted only after every compiled TEI div has
        // been flushed, so its FK can never compete with the self-referencing
        // TEI tree in Hibernate's action queue.
        persistOpusMetadata(allImportedOpuses);
        signalEventNewOpuses(allImportedOpuses);

        watch.stop();
        log.info("done parsing {}, {} root divs, took {}", name, opusCounter, watch);
        return resp;
    }

    private void persistOpusMetadata(List<TeiDiv> importedOpuses) {
        if (teiOpusRepository == null) return; // parser-only unit fixtures
        final List<ro.editii.scriptorium.model.TeiOpus> metadata = importedOpuses.stream()
                .filter(TeiDiv::isOpus)
                .map(opus -> {
                    // Resolve the just-flushed root again.  This keeps the
                    // one-to-one envelope attached to a managed TeiDiv even
                    // when parsing was entered without a caller transaction.
                    if (opus.getId() == null) {
                        log.warn("skipping opus metadata for {} because its parsed div has no id", opus.getXpath());
                        return null;
                    }
                    // Resolve a fully managed entity (rather than a lazy
                    // proxy) so the metadata row and the subsequent event
                    // serialization never outlive the persistence session.
                    final TeiDiv persisted = entityManager == null
                            ? opus : entityManager.find(TeiDiv.class, opus.getId());
                    if (persisted == null) {
                        log.warn("skipping opus metadata for missing div {}", opus.getId());
                        return null;
                    }
                    return new ro.editii.scriptorium.model.TeiOpus(
                            persisted, openingDescriptions.get(opus));
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!metadata.isEmpty()) teiOpusRepository.saveAllAndFlush(metadata);
    }

    /** A conservative import-time fallback: only the opening two paragraphs. */
    private static String openingDescription(Node opusNode) {
        final List<String> paragraphs = new ArrayList<>();
        final NodeList descendants = opusNode.getChildNodes();
        collectParagraphs(descendants, paragraphs);
        if (paragraphs.isEmpty()) {
            final String text = opusNode.getTextContent();
            if (text != null && !text.isBlank()) paragraphs.add(text);
        }
        final String normalized = paragraphs.stream().limit(2)
                .map(it -> it.replaceAll("\\s+", " ").trim())
                .filter(it -> !it.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
        if (normalized.isBlank()) return null;
        return normalized.length() <= 600 ? normalized : normalized.substring(0, 600).trim() + "…";
    }

    private static void collectParagraphs(NodeList nodes, List<String> paragraphs) {
        for (int i = 0; i < nodes.getLength(); i++) {
            final Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE &&
                    ("p".equals(node.getLocalName()) || "p".equals(node.getNodeName()) ||
                            node.getNodeName().endsWith(":p"))) {
                paragraphs.add(node.getTextContent());
            } else if (node.hasChildNodes()) {
                collectParagraphs(node.getChildNodes(), paragraphs);
            }
            if (paragraphs.size() >= 2) return;
        }
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

        final Set<String> usedFragments;
        if (parentDiv == null) {
            final Author author = div.getTeiFile().getAuthor();
            usedFragments = this.teiDivRepository.findOperaForAuthorStrId(author.getStrId()).stream()
                    .map(opus -> opus.getUrlFragment())
                    .collect(java.util.stream.Collectors.toSet());
        } else if (parentDiv.getDbChildren() == null) {
            usedFragments = Set.of();
        } else {
            usedFragments = parentDiv.getDbChildren().stream()
                    .map(child -> child.getUrlFragment())
                    .collect(java.util.stream.Collectors.toSet());
        }

        final CandidateUrlFragmGeneratorForTeiDivHead iterable = new CandidateUrlFragmGeneratorForTeiDivHead(div.getHead());
        for (String candidate : iterable) {
            // Root candidates are checked against same-author works (another
            // edition may already use the title); nested candidates are
            // checked against this parent's children. Each source is read
            // once, even when several candidate fragments collide.
            if (!usedFragments.contains(candidate))
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
            // positional fast path for TeiElem.getNode() - see its own comment;
            // computed here, where the DOM is already walked anyway
            div.setDomPath(XpathTool.getDomPath(node));

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
            if (div.isOpus()) openingDescriptions.put(div, openingDescription(node));

            if (acc.size() >= 1000) {
                // Flush each batch before any TeiOpus FK row is written;
                // leaving earlier chunks pending lets Hibernate interleave
                // the metadata insert with the self-referencing TEI batch.
                flushDivBatch(acc);
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
     * Flush a compiled TEI batch before the metadata phase begins. The first
     * element is the root div; giving it its own flush guarantees the target
     * row exists before descendants and the later TEI_OPUS FK are written,
     * while descendants still use normal JDBC batching.
     */
    private void flushDivBatch(List<TeiDiv> divs) {
        if (divs.isEmpty()) return;
        this.teiDivRepository.saveAllAndFlush(List.of(divs.get(0)));
        if (divs.size() > 1)
            this.teiDivRepository.saveAllAndFlush(divs.subList(1, divs.size()));
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
