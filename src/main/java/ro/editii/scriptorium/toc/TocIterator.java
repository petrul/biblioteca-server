package ro.editii.scriptorium.toc;

import ro.editii.scriptorium.model.TeiDiv;

import java.util.Iterator;
import java.util.NoSuchElementException;

/** Iterates only leaf divs in the table of contents' document order. */
public class TocIterator implements Iterator<TeiDiv> {
    private final Iterator<TeiDiv> divs;
    private TeiDiv next;

    public TocIterator(TeiDiv root) {
        this(new Toc(root));
    }

    public TocIterator(Toc toc) {
        this.divs = toc.iterator();
    }

    @Override
    public boolean hasNext() {
        while (next == null && divs.hasNext()) {
            TeiDiv candidate = divs.next();
            if (candidate.isLeaf()) {
                next = candidate;
            }
        }
        return next != null;
    }

    @Override
    public TeiDiv next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        TeiDiv result = next;
        next = null;
        return result;
    }
}
