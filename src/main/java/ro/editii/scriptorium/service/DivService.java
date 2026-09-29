package ro.editii.scriptorium.service;

import editii.commons.xml.TeiDocument;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Node;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.cache.CacheConf;
import ro.editii.scriptorium.cache.DiskCache;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.rest.RestUtil;
import ro.editii.scriptorium.tei.TeiRepo;
import ro.editii.scriptorium.toc.Toc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service @Log4j2 @RequiredArgsConstructor
public class DivService {

    final TeiDivRepository teiDivRepository;
    final TeiFileRepository teiFileRepository;
    final TeiRepo teiRepo;

    @PersistenceContext
    EntityManager entityManager;

    @Autowired @Qualifier(CacheConf.CACHE_TOC)
    DiskCache cacheToc;

    @Autowired @Qualifier(CacheConf.CACHE_NODE)
    DiskCache cacheNode;

    @Autowired @Qualifier(CacheConf.CACHE_BINARY_OBJECT)
    DiskCache cacheBinaryObject;

    public List<TeiDiv> getOpera(String authorStrid) {
        return this.teiDivRepository.findOperaForAuthorStrId(authorStrid);
    }

    /**
     * this method is doubly cached. once by a manual lookup inside
     * a {@link DiskCache} and secondly, using the spring @Cacheable
     * annotation.
     */
    @Cacheable("toc")
    @Transactional
    public Toc getToc(long opId) {

        TeiDiv div = this.teiDivRepository.findById(opId).get();
        final Long id = div.getId();
        final Object cached = this.cacheToc.getAt(id);
        if (cached != null)
            return (Toc) cached;

        div = this.entityManager.merge(div);

        final Toc toc = new Toc(div);
        this.cacheToc.putAt(id, toc);
        return toc;
    }

    /**
     * this method is doubly cached. once by a manual lookup inside
     * a {@link DiskCache} and secondly, using the spring @Cacheable
     * annotation.
     */
    @Cacheable("node")
    public Node getNode(TeiElem elem) {
        elem.setTeiRepo(this.teiRepo);
        final Integer uniqueId = elem.hashCode();

        final Object cached = this.cacheNode.getAt(uniqueId);
        if (cached != null)
            return (Node) cached;

        final Node resp = elem.getNode();
        this.cacheNode.putAt(uniqueId, resp);
        return resp;
    }

    /**
     * @param path of the form /author/opus/div1/div2 ...
     */
    @Cacheable("tei_elem_by_path")
    @Transactional
    public TeiElem getByPath(String path) {
        final var fragms = Util.pathFragments(path);

        if (fragms.length < 2)
            RestUtil.throw400("path must be of the form '/author/opus/optional/remaining/subpath/and/subchapters");

        final var author = fragms[0];
        final var opus = fragms[1];

        final var rejoined = "/" + String.join("/", fragms);
        final var elem = this.retrieveElem(author, opus, rejoined);

        assert elem.getTeiFile() != null;
        return elem;
    }

    /**
     * @param path i.e. /author/opus/rest/of/path
     *             contains the whole complete path, including the author, as first fragment,
     *             the opus name as second fragment, and, if any, the rest.
     * @return a couple contains first the Opus TeiDiv and the second, the actual TeiDiv corresponding to path
     */
    @Transactional
    protected TeiElem retrieveElem(String authorId, String opusId, String path) {

        assert authorId != null;
        assert opusId   != null;
        assert ! authorId.isBlank();
        assert ! opusId.isBlank();

        final TeiDiv opus = this.getOpus(authorId, opusId);

        TeiElem crt = opus;
        final var teiFile = opus.getTeiFile();
        assert teiFile != null;

        // 3. iterative : for each crt: div1,div2,div3 from /author/opusId/div1/div2/div3

        // endPart is path without author and opusid (the divs)
        final String pathEnd = this.pathProper(authorId, opusId, path);

        final String[] fragms = Util.pathFragments(pathEnd);
        for (String fragm: fragms) {
            final var ftype = FragmType.from(fragm);
            crt = ftype.getChildElem(crt);
            crt.setTeiFile(teiFile);

            if (crt.isDiv()  && !(crt instanceof TeiDiv)) {
                // if element is div but not gotten from the db, re-search it in the db so we can
                // get the full object with additional metadata (like TeiDiv dbChildren,
                // xpath and urlFragment computed at parsing))
                final Optional<TeiDiv> byTeiFileAndXpath = this.teiDivRepository.getByTeiFileAndXpath(teiFile, crt.getXpath());
                assert byTeiFileAndXpath.isPresent();
                crt = byTeiFileAndXpath.get();
            }

            crt.setTeiRepo(this.teiRepo);
        }

        assert crt.getTeiFile() != null;

        return crt;
    }

    /**
     * Resolves the 1-indexed nth element child of parent (see
     * TeiElem.elemChild), re-hydrating it from the DB if it turns out to be
     * a TeiDiv - the same rehydration retrieveElem does per path segment
     * above, needed because TeiElem.elemChild's div branch
     * (divElemChildForParent) doesn't attach teiFile/teiRepo on its own,
     * unlike its non-div branch: it expects the caller to swap in the real
     * persisted TeiDiv (with dbChildren, xpath, urlFragment) instead.
     * Used directly (rather than through a URL path fragment) by callers
     * that navigate by raw child index, e.g. FragmentResolutionService.
     */
    public TeiElem childElem(TeiElem parent, int nth) {
        final var teiFile = parent.getTeiFile();
        TeiElem child = TeiElem.elemChild(parent, nth);
        child.setTeiFile(teiFile);

        if (child.isDiv() && !(child instanceof TeiDiv)) {
            final Optional<TeiDiv> byTeiFileAndXpath = this.teiDivRepository.getByTeiFileAndXpath(teiFile, child.getXpath());
            assert byTeiFileAndXpath.isPresent();
            child = byTeiFileAndXpath.get();
        }

        child.setTeiRepo(this.teiRepo);
        return child;
    }


    /**
     * @return the end part of the path, without the author and opus name.
     */
    private String pathProper(String authorId, String opusId, String path) {

        final String expectedBeginning = String.format("/%s/%s", authorId, opusId);
        if (! path.startsWith(expectedBeginning))
            RestUtil.throw400(String.format("expected url to start with %s", expectedBeginning));

        String endPartOfUrl = path.substring(expectedBeginning.length());

        if (endPartOfUrl.contains(".")) {
            // remove extension if any
            endPartOfUrl = endPartOfUrl.split("\\.")[0];
        }
        if (endPartOfUrl.startsWith("/"))
            // remove beginning slash if any
            endPartOfUrl = endPartOfUrl.substring(1);

        return endPartOfUrl;
    }

    /**
     * an opus is a level-0 TeiDiv.
     */
    @Cacheable("opus")
    public TeiDiv getOpus(String authorId, String opusId) {
        final List<TeiDiv> opera = this.teiDivRepository.findOperaForAuthorStrId(authorId)
                .stream()
                .filter( teidiv -> teidiv.getUrlFragment().equals(opusId))
                .toList();

        if (opera.isEmpty())
            RestUtil.throw404(String.format("no opus for [%s/%s]", authorId, opusId));

        if (opera.size() > 1)
            RestUtil.throw500(String.format("several (%d) divs correspond to this url (%s), expected exactly 1", opera.size(), opusId));

        final TeiDiv op = opera.get(0);
        op.setTeiRepo(this.teiRepo);

        return op;
    }


    public byte[] getBinaryObject(String authorId, String opusId, String binaryObjectId) {

        final var binobjStrId = String.format("%s_%s_%s", authorId, opusId, binaryObjectId);

        if (! this.cacheBinaryObject.has(binobjStrId)) {
            final TeiDiv op = this.getOpus(authorId, opusId);
            final Node rootNode = this.getNode(op);
            final TeiDocument tei = new TeiDocument(rootNode);
            final byte[] bytes = tei.getBinaryObject(binaryObjectId);
            this.cacheBinaryObject.putAt(binobjStrId, bytes);
            return bytes;
        } else
            return (byte[]) this.cacheBinaryObject.getAt(binobjStrId);
    }

    // @Transactional here (not just on getToc()) because getToc() is called
    // via self-invocation (this.getToc(...)) from inside getParagraphs()'s
    // call chain below - Spring's proxy-based AOP doesn't intercept
    // self-invocations, so getToc()'s own @Transactional silently never
    // fires on that path, and its entityManager.merge() then blows up with
    // "No EntityManager with actual transaction available" on a cold cache
    // (it only "worked" before when getToc()'s manual DiskCache lookup
    // happened to already be warm from an earlier, properly-proxied call).
    // Annotating these actual external entry points establishes the
    // transaction up front, which the nested self-invoked getToc() then
    // just participates in (default REQUIRED propagation).
    @Transactional
    public List<TeiElem> getParagraphs(TeiDiv teiDiv) {
        return this.getParagraphs(teiDiv, true);
    }

    @Transactional
    public List<TeiElem> getParagraphs(TeiDiv teiDiv, long offset, int limit) {
        return this.getParagraphs(teiDiv, true, offset, limit);
    }

    /**
     *  get all 'paragraphs' of teiDiv, that is the first-level children of divs (can be
     *  paragraphs, lg, table etc)
     */
    public List<TeiElem> getParagraphs(TeiDiv teiDiv, boolean excludeLicense) {
        return this.getParagraphs(teiDiv, excludeLicense, 0, Integer.MAX_VALUE);
    }

    /**
     * Get a slice of the non-div children without materializing all preceding
     * and following paragraphs in a single list.
     */
    public List<TeiElem> getParagraphs(TeiDiv teiDiv, boolean excludeLicense, long offset, int limit) {
        if (offset < 0)
            throw new IllegalArgumentException("offset must not be negative");
        if (limit < 0)
            throw new IllegalArgumentException("limit must not be negative");
        if (limit == 0)
            return List.of();

        final var toc = this.getToc(teiDiv.getId());
        final var page = new ArrayList<TeiElem>(Math.min(limit, 1000));
        long remainingToSkip = offset;

        for (TeiDiv div: toc) {
            if (excludeLicense && div.isLicense())
                continue;

            div.setTeiRepo(this.teiRepo);
            final var allElems = div.getChildrenElements();
            for (TeiElem elem : allElems) {
                if (elem.isDiv())
                    continue;
                if (remainingToSkip > 0) {
                    remainingToSkip--;
                    continue;
                }
                if (page.size() == limit)
                    return page;
                page.add(elem);
            }
        }

        return page;
    }

    public TeiElem getById(Long divid) {
        final Optional<TeiDiv> opt = this.teiDivRepository.findById(Long.valueOf(divid));
        if (opt.isEmpty()) return null;

        final TeiDiv teiDiv = opt.get();
        teiDiv.setTeiRepo(this.teiRepo);
        return teiDiv;
    }
}


enum FragmType  { NTH, HEAD;

    public static final char NTH_INDICATOR = '_';

    public static FragmAndType from(String id) {
        if (id.charAt(0) == NTH_INDICATOR) {
            final var rest = id.substring(1, id.length());
            return new NthFragm(Integer.parseInt(rest));
        }
        return new DivHeadFragm(id);
    }
}
@Data @AllArgsConstructor
abstract class FragmAndType {
    String fragm;
    FragmType type;
    Integer nth;
    public abstract TeiElem getChildElem(TeiElem elem);
}
/*
    represents a URL fragment that uses an index (nth) to retrieve a child.

    usable for any TeiElem. the child identification is the number, starting at 1.
 */
class NthFragm extends FragmAndType {
    public NthFragm(int nth) { super(null, FragmType.NTH, nth);}

    @Override
    public TeiElem getChildElem(TeiElem parent) {
        return TeiElem.elemChild(parent, this.nth);
    }
}

/*
  usable for mysql-stored TeiDivs. the child identification is the urlFragment corresponding the div's head.
 */
class DivHeadFragm extends FragmAndType {
    public DivHeadFragm(String head) { super(head, FragmType.HEAD, null); }

    @Override
    public TeiElem getChildElem(TeiElem elem) {
        assert elem instanceof TeiDiv;
        return elem.getChildByUrlFragment(this.fragm);
    }
}
