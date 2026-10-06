package ro.editii.scriptorium.dav;

import ro.editii.scriptorium.model.Languages;

/**
 * One (opus, author) row of the DAV export's slim opus listing: the routing
 * fields the path resolver needs, nothing else - no entity graph, no LOB
 * columns. Rows are one per author (an opus with no author rows carries
 * null author fields); DavExportService groups them by divId.
 *
 * @see ro.editii.scriptorium.dao.TeiDivRepository#findAllOperaRowsForDav()
 */
public record DavOperaRow(
        long divId,
        String filename,
        String urlFragment,
        String head,
        Languages language,
        String authorStrId,
        String authorDisplayName,
        String authorFirstName,
        String authorLastName,
        long childCount) {

    /**
     * Author.getVisualName()'s exact logic, on the row's mapped columns -
     * JPQL cannot select the getter (it is not a persistent attribute,
     * which is what broke query validation at startup).
     */
    public String authorVisualName() {
        if (authorDisplayName != null)
            return authorDisplayName;
        if (authorFirstName == null)
            return authorLastName;
        return authorFirstName + " " + authorLastName;
    }
}
