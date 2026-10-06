package ro.editii.scriptorium.dao;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.dav.DavOperaRow;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.Languages;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiFile;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeiDivRepository extends JpaRepository<TeiDiv, Long> {

    List<TeiDiv> findByHead(String head);
    List<TeiDiv> findByTeiFile(TeiFile teiFile);
    List<TeiDiv> findByTeiFileAndXpath(TeiFile teiFile, String xpath);
    Optional<TeiDiv> getByTeiFileAndXpath(TeiFile teiFile, String xpath);

    /**
     * return "root" divs, corresponding to h1 in office file, should be an opus,aka work name
     */
    @Query("""
            select div from TeiDiv div 
            join div.teiFile tf 
            where div.parent is null and tf.id = ?1
            """)
    List<TeiDiv> getOperaForTeiFileId(long teiFileId);

    /**
     * Lightweight variant for housekeeping paths. Do not load whole TeiDiv
     * entities here: Derby exposes @Lob fields through transaction-bound
     * locators, while pruning only needs the stable URL paths.
     */
    @Query("""
            select concat(a.strId, '/', div.urlFragment) from TeiDiv div
            join div.teiFile tf
            join tf.authors a
            where div.parent is null and tf.id = ?1
            """)
    List<String> getOperaPathsForTeiFileId(long teiFileId);

    // Random-discovery picks, in native SQL on purpose: the JPQL equivalent
    // (full-entity join + setFirstResult, or even the slim `dbChildren is
    // empty` projection) lets Hibernate build a plan Derby runs in ~0.8s at
    // this table size, while this not-exists form costs ~0.5-1s ONCE -
    // the ids are then picked from in memory (cached in UtilController,
    // since the corpus changes only on import).
    @Query(value = """
            select e.id from "_tei_elem" e
            where e.name = 'div'
            and not exists (select 1 from "_tei_elem" c where c."parent_id" = e.id)
            """, nativeQuery = true)
    List<Long> getBottomDivIds();

    @Query(""" 
             select div from TeiDiv div 
             join div.teiFile.authors a 
             where div.parent is null 
             and a.strId = ?1 """)
    List<TeiDiv> findOperaForAuthorStrId(String authorStrId);

    @Query("""
            select count(div) from TeiDiv div
            join div.teiFile tf
            join tf.authors a
            where div.parent is null and a.strId = ?1
            """)
    long countOperaForAuthorStrId(String authorStrId);

    Page<TeiDiv> findByHeadContainingIgnoreCase(String excerpt, Pageable page);
    Page<TeiDiv> findByLang(Languages lang, Pageable pageable);

    // Explicit order (not just "whatever MySQL happens to return") - both
    // LuceneIndexService's resumable rebuild and GrepSearchService page
    // through this without any filter, and rely on page boundaries staying
    // stable across separate query executions (e.g. resuming a rebuild
    // after a restart) to never skip or duplicate an opus.
    @Query("select div from TeiDiv div where div.parent is null order by div.id")
    Page<TeiDiv> findOpera(Pageable pageable);

    @Query("""
            select div from TeiDiv div
            join div.teiFile tf
            join tf.authors a
            where div.parent is null
              and (:language is null or tf.language = :language)
              and (:q is null or :q = ''
                   or lower(div.head) like lower(concat('%', :q, '%'))
                   or lower(a.strId) like lower(concat('%', :q, '%'))
                   or lower(a.firstName) like lower(concat('%', :q, '%'))
                   or lower(a.lastName) like lower(concat('%', :q, '%')))
            order by div.id
            """)
    Page<TeiDiv> findOperaCatalogPage(@org.springframework.data.repository.query.Param("q") String query,
                                       @org.springframework.data.repository.query.Param("language") Languages language,
                                       Pageable pageable);

    @Query("select div from TeiDiv div where div.parent is null")
    @EntityGraph(attributePaths = {"teiFile", "teiFile.authors"})
    List<TeiDiv> findAllOpera();

    /**
     * The DAV export's opus listing: routing fields only, one row per
     * (opus, author; authorless opera have null author fields). The DAV
     * service resolves paths against this slim projection instead of
     * findAllOpera()'s full entity graph - the summary LOB plus the eager
     * opusMetadata/teiFile/authors per opus made every DAV request
     * materialize thousands of entities (tens of seconds per PROPFIND).
     * The one work a path actually descends into is loaded whole
     * afterwards, via findOperaByStablePath.
     */
    @Query("""
            select new ro.editii.scriptorium.dav.DavOperaRow(
                div.id, tf.filename, div.urlFragment, div.head, tf.language,
                a.strId, a.displayName, a.firstName, a.lastName, size(div.dbChildren))
            from TeiDiv div
            join div.teiFile tf
            left join tf.authors a
            where div.parent is null
            """)
    List<DavOperaRow> findAllOperaRowsForDav();

    /**
     * Resolve one curated work by its stable authorId/opusId path.  Featured
     * collections must not load every root work (and all author rows) just to
     * discard almost all of them in memory.
     */
    @Query("""
            select div from TeiDiv div
            join div.teiFile tf
            join tf.authors a
            where div.parent is null
              and a.strId = ?1
              and div.urlFragment = ?2
            """)
    @EntityGraph(attributePaths = {"teiFile", "teiFile.authors"})
    Optional<TeiDiv> findOperaByStablePath(String authorId, String opusId);

    @Query("""
            from TeiDiv div
            where div.parent is null
            and div.lang = ?1
            """)
    Page<TeiDiv> findOperaByLang(Languages lang, Pageable pageable);

    List<TeiDiv> findByUrlFragmentAndParent(String urlFragment, TeiDiv parent);

    @Query("""
        select d.teiFile.authors
        from TeiDiv d
        where d.id = ?1
        """)
    List<Author> getAuthors(long id);

    // save/delete used to be @RestResource(exported = false) here - DREST
    // writes were disabled while /api/drest/** was reachable from the
    // internet (see biblioteca-server/README.md). Now that it never is
    // (reader is the only bridge, biblioteca-nestjs the only other
    // caller, both on the trusted network), the default exported
    // save/delete apply.
}
