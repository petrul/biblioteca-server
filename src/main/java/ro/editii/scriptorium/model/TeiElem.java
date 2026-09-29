package ro.editii.scriptorium.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import editii.commons.xml.DomTool;
import editii.commons.xml.XpathTool;
import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.collections4.map.LRUMap;
import org.apache.commons.lang3.time.StopWatch;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dto.TeiElemDto;
import ro.editii.scriptorium.rest.RestUtil;
import ro.editii.scriptorium.service.ElemInfo;
import ro.editii.scriptorium.tei.TeiRepo;

import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Entity
@DiscriminatorColumn(name="name", discriminatorType = DiscriminatorType.STRING)
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@Table(indexes = {@Index(columnList = "urlFragment")})
@Data
@Log4j2 @NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true) @ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TeiElem implements Comparable<TeiElem>, Serializable  {

    // Must stay in sync with TeifileParser.TEI_BODY_XPATH_PREFIX, which
    // computes the other direction (full xpath -> relative stored path) -
    // see its own comment for why <text> is bracketed but <body> is not.
    public static final String TEI_TEXT_BODY = "/tei:TEI/tei:text[1]/tei:body";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tei_elem_seq")
    @SequenceGenerator(name = "tei_elem_seq", sequenceName = "tei_elem_seq", allocationSize = 50)
    @EqualsAndHashCode.Include
    Long id;

    final public static int MAX_URL_FRAGM_SIZE = 100;

    @ManyToOne
    @EqualsAndHashCode.Include @ToString.Exclude @JsonIgnore
    protected TeiFile teiFile;

    /**
     *  the TEI xml xpath needed to reach this elem,
     *  relative to /tei:TEI/tei:text/tei:body
     */
    @EqualsAndHashCode.Include
    protected String xpath;

    /**
     *  positional DOM path ("2/3/7": the 1-based child index at each level
     *  from the parsed document's root, all node kinds counted - the same
     *  semantics as nth, but the full chain from the document). A
     *  resolution fast path for getNode() that needs no XPath engine:
     *  computed once at import (TeifileParser, right next to xpath).
     *  null for rows imported before this column existed - getNode()
     *  falls back to the xpath evaluation for those.
     */
    protected String domPath;

    @Enumerated(EnumType.STRING)
    Languages lang;

    /**
     * element name:i.e.:  p, lg, table  (without the tei: prefix).
     */
    @Column(name = "name", length = 50, insertable=false, updatable=false)
    protected String name;

    /**
     * nth child of parent elem, first one being 1 (not 0, we're in xpath),
     * all elems included, divs and inter-element text and whitespace and everything.
     */
    protected int nth;

    @Size(max = MAX_URL_FRAGM_SIZE) @Column(length = MAX_URL_FRAGM_SIZE)
    protected String urlFragment;

    /**
     * the sha256 hash for the txt version of this if available or null if not
     */
    @Column(length = 32)
    protected byte[] txtSha256;

    @ToString.Exclude @JsonIgnore
    @ManyToOne
    protected TeiElem parent;

    /**
     * usable most often for getting subchapters. (sub-divs).
     *
     * this is the list of the TeiElem that have this as parent in the SQL db.
     *
     * it is most often the <div> children of this; which are actually stored in the db.
     *
     * it is NOT the entirety of XML node children corresponding to this.
     * if you rather want the exhaustive list of XML children, do a this.getNode().getChildren()
     * and you will get a Java DOM XML structure which you can iterate on.
     */
    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
    @ToString.Exclude
    @JsonIgnore // would lead to very large listings
    protected List<TeiElem> dbChildren;

    @ToString.Exclude
    int size; // approximate size of this in characters
    @ToString.Exclude
    int wordSize; // approx size in words (continuous non-space chars)

    @JsonIgnore
    public List<TeiDiv> getDbChildrenAsDivs() {
        if (this.dbChildren == null) return null;
        return this.dbChildren.stream().map(it -> (TeiDiv) it).toList();
    }

    @ToString.Exclude @JsonIgnore
    @Transient
    protected transient TeiRepo teiRepo; // this is not stored but it is needed for getNode()

    @ToString.Exclude
    @Transient  @JsonIgnore @Builder.Default
    private Node _node = null; // cache
    @JsonIgnore
    public Node getNode() {
        if (_node != null)
            return _node;

        if (this.teiRepo == null)
            throw new IllegalStateException("you must set a TeiRepo in order to retrieve the file content of teiFile");

        final XpathTool xpathTool = parseTeiFile();

        // Fast path: positional resolution, no XPath engine involved. The
        // name check turns a stale path (document edited since import)
        // into a fallback to the xpath below, which then reports a moved
        // or missing element with its familiar IllegalStateException -
        // instead of the path silently resolving to a wrong node.
        final String domPath = this.getDomPath();
        if (domPath != null) {
            final Node resolved = XpathTool.resolveDomPath(xpathTool.getRoot(), domPath);
            if (resolved != null && (this.getName() == null || this.getName().equals(resolved.getLocalName())))
                return this._node = DomTool.deepCopy(resolved);
        }

        String xpath_expr = TEI_TEXT_BODY + this.getXpath();
        if (xpath_expr.endsWith("/")) // remove trailing "/"
            xpath_expr = xpath_expr.substring(0, xpath_expr.length() - 1);

        final NodeList nodeList = xpathTool.applyXpathForNodeSet(xpath_expr);

        if (nodeList.getLength() != 1)
            throw new IllegalStateException(
                    String.format(
                            "Expected exactly one div to correspond to xpath [%s], instead got %s",
                            xpath_expr,
                            nodeList.getLength() == 0 ? "none" : Integer.toString(nodeList.getLength()))
            );
        this._node = DomTool.deepCopy(nodeList.item(0));

        return this._node;
    }


    // cache: parsing a tei file is expensive so cache so we don't parse a new file for every div.
    final private static LRUMap<String, XpathTool> cache_parsedFiles = new LRUMap<>(10, 10);

    protected XpathTool parseTeiFile() {
        final String filename = this.teiFile.getFilename();
        synchronized (cache_parsedFiles) {

            XpathTool cached = cache_parsedFiles.get(filename);

            if (cached == null) {
                final StopWatch watch = new StopWatch(); watch.start();

                final InputStream is = teiRepo.getStreamForName(filename);
                final XpathTool xpathTool = new XpathTool(is, filename); // this parses the file too
                cache_parsedFiles.put(filename, xpathTool);
                cached = xpathTool;

                watch.stop();
                log.info("parsed {} in thread {}, took {}", filename,
                        Thread.currentThread().getName(), watch);
            }

            return cached;
        }
    }

    /**
     * @return the root-level {@link TeiDiv} which is actuall the work
     */
    @ToString.Exclude
    @Transient @JsonIgnore  @Builder.Default
    private TeiDiv _opus = null; // cache for opus
    /**
     * @return the parent work of this div, by recursively inspecting
     * parents' parents until null.
     */
    @JsonIgnore
    public TeiDiv getOpus() {
        if (this._opus == null) {
            if (this.parent == null)
                this._opus = (TeiDiv) this;
            else
                this._opus = this.parent.getOpus();
        }

        return this._opus;
    }

    @JsonIgnore
    /**
     *
     * @return the closest {@link TeiDiv} to which this belongs, which should be a chapter or sub-chapter
     */
    public TeiDiv getDiv() {
        if (this instanceof TeiDiv)
            return (TeiDiv) this;
        else
            return this.parent.getDiv();
    }

    public TeiElemDto toDto(UriComponentsBuilder uriComponentsBuilder) {

        final var self = this;

        final var url = uriComponentsBuilder
                .path(self.getCompletePath())
                .build().toUriString();

        final var lang = self.getTeiFile() != null ? self.getTeiFile().getLanguage() : null;

        return new TeiElemDto() {{
            setId(self.getId());
            setName(self.getName());
//            setXpath(self.getXpath());
            setPath(self.getCompletePath());
//            setUrlFragment(self.getUrlFragment());
            setUrl(url);
            depth = self.getDepth();
            size = self.getSize();
            wordSize = self.getWordSize();
            setLanguage(lang != null ? lang.getISO639_1Code() : null);
        }};
    }


    // this is the concatenated urlFragment of this and its ancestors -- starting from author really.
    @ToString.Exclude @JsonIgnore
    @Builder.Default
    @Transient
    protected String _url = null; // cache
    @JsonIgnore
    public String getUrl() {
        if (this._url == null) {
            if (this.parent == null)
                _url = this.getUrlFragment();
            else
                _url = this.getParent().getUrl() + "/" + this.getUrlFragment();
        }
        return _url;
    }

    @ToString.Exclude @JsonIgnore
    @Transient  @Builder.Default
    int depth = -1;
    public int getDepth() {
        if (depth < 0) {
            if (this.parent == null)
                this.depth = 0;
            else
                this.depth = 1 + this.parent.getDepth();

        }
        return this.depth;
    }

    @JsonIgnore
    public List<TeiElem> getBreadcrumb() {
        final List<TeiElem> breadcrumb = new ArrayList<>();
        TeiElem divIt = this;
        while (divIt != null) {
            breadcrumb.add(divIt);
            divIt = divIt.getParent();
        }
        Collections.reverse(breadcrumb);
        return breadcrumb;
    }

    // this is really the complete _path_ that follows after the base /
    public String getCompletePath() {
        return this.getAuthor().getStrId() + "/" + this.getUrl();
    }

    public Author getAuthor() {
        return this.getTeiFile().getAuthor();
    }


    public boolean isOpus() {
        return this.getParent() == null;
    }

    public boolean isLeaf() {
        return this.getDbChildren() == null || this.getDbChildren().isEmpty();
    }

    /**
     * retrieves language information as described in the TEI file, if any
     */
    @JsonIgnore
    public String getTeiLanguage() {
        final Node node = this.getNode();
        final XpathTool xp = new XpathTool(node);
        return xp.xpath("/tei:TEI/tei:teiHeader//tei:profileDesc//tei:language");
    }

    @JsonIgnore
    public String getLicense() {
        final Node node = getNode();
        return new XpathTool(node).xpath("/tei:TEI/tei:teiHeader//tei:publicationStmt");
    }

    @JsonIgnore
    public Node getSourceDesc() {
        final Node node = getNode();
        final NodeList nodes = new XpathTool(node).applyXpathForNodeSet("/tei:TEI/tei:teiHeader//tei:sourceDesc");
        if (nodes.getLength() > 0)
            return nodes.item(0);
        else
            return null;
    }

    @ToString.Exclude
    @Transient @JsonIgnore @Builder.Default
    private String _cacheRelativeRoot = null;
    /**
     * @return ../../..  with a slash before, don't remember precisely why this slash is needed
     */
    @JsonIgnore
    public String getRelativeRoot() {
        if (_cacheRelativeRoot == null) {
            String resp;
            resp = IntStream.range(0, this.getDepth())
                    .mapToObj(it -> "..")
                    .collect(Collectors.joining("/"));
            if (!resp.isEmpty()) resp = "/" + resp;
            _cacheRelativeRoot = resp;
        }
        return _cacheRelativeRoot;
    }

    public void addChild(TeiElem teiElem) {
        if (this.dbChildren == null)
            this.dbChildren = new ArrayList<>();
        this.dbChildren.add(teiElem);
    }


    public TeiElem getChildByUrlFragment(String urlFragment) {
        final List<TeiElem> children = this.getDbChildren();

        final List<TeiElem> byFragm = children.stream()
                .filter(it -> urlFragment.equals(it.getUrlFragment()))
                .toList();

        if (byFragm.isEmpty()) {
            RestUtil.throw404(String.format("no such div [%s]", urlFragment));
        }

        if (byFragm.size() > 1) {
            throw new IllegalStateException(
                String.format(
                        "expected precisely one child for urlFragment [%s], instead found (%d)",
                        urlFragment, byFragm.size()));
        }

        return byFragm.get(0);
    }

    /**
     * despite having several other fields,
     * a TeiElem is rendered really unique by its {@link TeiFile} and xpath.
     */
    @Override
    public int compareTo(TeiElem other) {

        if (this == other) return 0;

        final TeiFile thisFile = this.getTeiFile();
        final TeiFile thatFile = other.getTeiFile();

        final int fileCompare = thisFile.compareTo(thatFile);
        if (fileCompare == 0) {
            return  this.getXpath().compareTo(other.getXpath());
        } else
            return fileCompare;

    }

    @JsonIgnore
    public String getNodeAsXmlString() {
        final Node node = this.getNode();
        return DomTool.serialize(node);
    }

    @JsonIgnore
    public String getNodeAsText() {
        return this.getNode().getTextContent();
    }

    @JsonIgnore
    /**
     * Actually inspects this elem's associated DOM Node to retrieve all children
     * elements (including divs).
     */
    public List<TeiElem> getChildrenElements() {
        final var thisNode = this.getNode();
        final List<Node> allChildElements = getAllChildElements(thisNode);
        final var resp = IntStream.range(0, allChildElements.size())
                .mapToObj(i -> {
                    final Node childNode = allChildElements.get(i);
                    return elemChildForParent(this, i + 1, childNode);
                }).toList();
        return resp;
    }

    /**
     * @param nth starts at 1
     * @return the nth element child. NB! inter-element text
     *  (usually whitespace but not necessarily) and XML comments are not returned.
     */
    public static TeiElem elemChild(TeiElem parent, int nth) {
        final Node parentNode = parent.getNode();
        final var onlyElements = getAllChildElements(parentNode);

        if (nth <= 0 || onlyElements.size() < nth) {
            throw new ResourceNotFoundException(String.format(
                    "no child element for nth %d (starting at 1)", nth));
        }
        final var child = onlyElements.get(nth - 1);
        assert child != null;

        TeiElem resp;
        final var childName = child.getNodeName();
        if (childName.equals(Util.DIV)) {
            final var onlyDivElements = onlyElements.stream()
                    .filter(it -> it.getNodeName().equals(Util.DIV))
                    .toList();
            // Must match XpathTool.getXPath's own bracket-presence rule
            // (any element sibling, not just same-name ones) - this
            // navigates to a node whose xpath TeifileParser already
            // stored using that rule, and the two must agree or a
            // by-xpath DB lookup for this exact node finds nothing.
            resp = divElemChildForParent(parent, nth, child, onlyDivElements, onlyElements.size() > 1);
        } else {
            resp = elemChildForParent(parent, nth, child);
        }


        return  resp;
    }

    private static TeiElem divElemChildForParent(TeiElem parent, int nth, Node child, List<Node> divElements, boolean hasAnyElementSibling) {
        final int indexOf = divElements.indexOf(child);
        assert indexOf >= 0;

        final String bracketSelector = hasAnyElementSibling ?
                String.format("[%d]", indexOf + 1) : "" ;

        final var xpath = String.format("%s/tei:div%s", parent.getXpath(), bracketSelector);
        final var resp = TeiElem.builder()
                .name(child.getNodeName())
                .parent(parent)
                .nth(nth)
                .xpath(xpath)
                .build();

        return resp;
    }

    /**
     * @param nth starts with 1
     */
    private static TeiElem elemChildForParent(TeiElem parent, int nth, Node child) {
        final var childName = child.getNodeName();

        // the xpath select '*' only selects XML elements (not text nodes, comments nor attributes)
        final var xpath = String.format("%s/*[%d]", parent.getXpath(), nth);

        final var resp = TeiElem.builder()
                .name(childName)
                .parent(parent)
                .nth(nth)
                .xpath(xpath)
                .urlFragment("_" + nth)
                .teiRepo(parent.getTeiRepo())
                .teiFile(parent.getTeiFile())
                .build();

        return resp;
    }

    /**
     * @return all child elements, (without comments, whitespace, inter-element text)
     */
    @NotNull
    private static List<Node> getAllChildElements(Node node) {
        final Collection<Node> allNodesIncludingCommentsTextEtc = DomTool.nodeList2Collection(node.getChildNodes());
        final var onlyElements = allNodesIncludingCommentsTextEtc.stream()
                .filter(it -> it.getNodeType() == Node.ELEMENT_NODE)
                .toList();
        return onlyElements;
    }

    @JsonIgnore
    public boolean isDiv() {
        return Util.DIV.equals(this.getName());
    }

    @JsonIgnore
    public boolean isBinaryObject() {
        return Util.BINARY_OBJECT.equals(this.getName());
    }

    /**
     * @return a string that can be used to represent this on the GUI (not as verbose as toString,
     * meaningful and pretty enough)
     */
    @JsonIgnore
    public String getVisualLabel() {
        return "_" + this.nth;
    }

    @JsonIgnore
    public ElemInfo toElemInfo() {
        final var elemInfo = ElemInfo.builder()
                .teiElem(this)
                .nodeCopy(DomTool.deepCopy(this.getNode())).
                build();
        return  elemInfo;
    }

}
