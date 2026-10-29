package ro.editii.scriptorium;

/**
 * Every per-request resource limit of biblioteca-server, in one place.
 * <p>
 * The server limits how much ONE request may ask for; it does not count
 * requests over time. Who may read how much per hour (people vs scrapers)
 * is the reader's job - biblioteca-reader's crawlerPageLimit.ts - since
 * the reader is the only public way in. A request asking for more than a
 * limit here is clamped silently, never refused, so a client that asks
 * for too much still gets the most it may have.
 * <p>
 * ResourceLimitsTest states each limit, where it applies and what happens
 * at it; change a value there and here together.
 */
public final class ResourceLimits {

    /** Works catalog and authors catalog pages (GET /api/works, /api/authors/page). */
    public static final int CATALOG_PAGE_SIZE_MAX = 100;

    /**
     * Results of any one search (GET /api/search/{authors,divHeads,grep,lucene,vector}).
     * The reader asks for max(50, page * pageSize + 1), growing as one pages
     * through results, so this leaves room for about 25 pages of 20.
     */
    public static final int SEARCH_RESULTS_MAX = 500;

    /**
     * Entries of one table-of-contents or paragraphs page
     * (GET /api/divs/{id}/toc, /api/divs/{id}/paras). The reader asks for
     * a whole work's TOC as 2000.
     */
    public static final int DIV_PAGE_SIZE_MAX = 2000;

    /** Paragraphs one grep search scans before returning what it found so far. */
    public static final int GREP_PARAGRAPHS_SCANNED_MAX = 50_000;

    /**
     * Spring Data REST page size (GET /api/drest/**). Applied through
     * spring.data.rest.max-page-size in application.properties, which must
     * stay equal to this.
     */
    public static final int DREST_PAGE_SIZE_MAX = 1000;

    private ResourceLimits() {}

    /** A page size within [1, max]. */
    public static int pageSize(int requested, int max) {
        return Math.min(max, Math.max(1, requested));
    }

    /** A 1-based page number, at least 1. */
    public static int pageNumber(int requested) {
        return Math.max(1, requested);
    }

    /** A 0-based page number, at least 0. */
    public static int pageIndex(int requested) {
        return Math.max(0, requested);
    }

    /** A search result count within [1, SEARCH_RESULTS_MAX]. */
    public static int searchResults(int requested) {
        return pageSize(requested, SEARCH_RESULTS_MAX);
    }
}
