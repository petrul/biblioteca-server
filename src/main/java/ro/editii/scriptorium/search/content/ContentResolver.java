package ro.editii.scriptorium.search.content;

/**
 * there is recurring proble this interface solves: getting content
 * corresponding to a given id.
 */
public interface ContentResolver {

    /**
     * get the content identified by the given id; null when it cannot be
     * resolved (the source is gone - a removed or renamed-away book, a
     * deleted paragraph). "Search data is precious": such stale hits are
     * retained in the index/vector store but never presented - callers
     * drop null-content hits instead of showing the user a dead link
     * (see VectorUtils.searchHitsToHits).
     */
    String resolve(String id);
}
