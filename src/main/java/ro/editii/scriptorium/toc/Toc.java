package ro.editii.scriptorium.toc;

import lombok.ToString;
import lombok.extern.log4j.Log4j2;
import org.springframework.transaction.annotation.Transactional;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;

import java.io.Serializable;
import java.util.*;

/**
 * a {@link Toc} is a 'table of contents' of sorts, in fact it's the list of
 * chapters and subchapters of a TeiDiv in depth-first order.
 *
 * it is useful for 'next', 'previous' navigation from a given fragment.
 */
@ToString @Log4j2
public class Toc implements Iterable<TeiDiv>, Serializable {

    List<TeiDiv> list_divs;
    final Map<Long, Integer> div_indices = new HashMap<>(); // maps TeiDiv.id =>  its index

    public Toc(TeiDiv div) {
        this.init(div);
    }

    private Toc() {}

    /**
     * The same table of contents as {@code new Toc(root)}, from rows loaded
     * in one query - each division of the work with its parent's id - instead
     * of one lazy dbChildren query per division (thousands for the Bible:
     * 12-15 s per cold page, see docs/caching-plan.md). Siblings are ordered
     * by id, as {@link TeiElem#getDbChildren()} orders them; rows outside
     * root's subtree are ignored.
     */
    public static Toc fromRows(TeiDiv root, Collection<DivRow> rows) {
        final Map<Long, List<TeiDiv>> childrenOf = new HashMap<>();
        for (DivRow row : rows) {
            if (row.parentId() != null) childrenOf.computeIfAbsent(row.parentId(), k -> new ArrayList<>()).add(row.div());
        }
        childrenOf.values().forEach(siblings -> siblings.sort(Comparator.comparing(TeiDiv::getId)));
        final Toc toc = new Toc();
        final List<TeiDiv> list = new ArrayList<>();
        if (root != null) {
            final Deque<TeiDiv> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                final TeiDiv div = stack.pop();
                toc.div_indices.put(div.getId(), list.size());
                list.add(div);
                final List<TeiDiv> children = childrenOf.getOrDefault(div.getId(), List.of());
                for (int i = children.size() - 1; i >= 0; i--) stack.push(children.get(i)); // first child on top
            }
        }
        toc.list_divs = list;
        return toc;
    }

    /** A division and its parent's id (null for a work's root), as one query returns them. */
    public record DivRow(TeiDiv div, Long parentId) implements Serializable {}

    public int size() {
        return this.list_divs.size();
    }

    private void init(TeiDiv div) {
        final TeiDiv crt = div;
        final List list = new LinkedList();

        parcurge_rec(crt, list);

        this.list_divs = list;
    }

    // parcuregere in depth a tree-ului div
    @Transactional
    void parcurge_rec(TeiDiv div, List<TeiDiv> buffer) {
        if (div == null)
            return;

        div_indices.put(div.getId(), buffer.size());
        buffer.add(div);

        final List<TeiElem> children = div.getDbChildren();

        if (children == null)
            return;

        for (TeiElem c : children) {
            if (c instanceof TeiDiv childDiv)
                parcurge_rec(childDiv, buffer);
        }
    }

    @Override
    public Iterator<TeiDiv> iterator() {
        return this.list_divs.iterator();
    }

    public TeiDiv prev(final TeiDiv div) {
        assert div           != null;
        assert div.getId()   != null;

        final Integer index = this.div_indices.get(div.getId());

        if (index == null)
            throw new IllegalArgumentException("this toc does not contain div " + div);

        if (index == 0)
            return null;

        return this.list_divs.get(index - 1);
    }

    public TeiDiv next(final TeiDiv div) {
        assert div           != null;
        assert div.getId()   != null;

        final Integer index = this.div_indices.get(div.getId());
        if (index == null)
            throw new IllegalArgumentException("this toc does not contain div " + div);

        if (index >= this.list_divs.size() - 1)
            return null;

        return this.list_divs.get(index + 1);
    }
}
