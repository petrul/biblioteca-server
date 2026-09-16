package ro.editii.scriptorium.dao;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.rest.core.annotation.RestResource;
import org.springframework.stereotype.Repository;
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

    // aka 'leaf' divs without div children
    @Query("""
        select count(parent) from TeiDiv parent 
        left outer join parent.dbChildren c 
        where c is null 
        """)
    int getNrOfBottomDivs();

    @Query(""" 
             select div from TeiDiv div 
             join div.teiFile.authors a 
             where div.parent is null 
             and a.strId = ?1 """)
    List<TeiDiv> findOperaForAuthorStrId(String authorStrId);

    Page<TeiDiv> findByHeadContainingIgnoreCase(String excerpt, Pageable page);
    Page<TeiDiv> findByLang(Languages lang, Pageable pageable);

    // Explicit order (not just "whatever MySQL happens to return") - both
    // LuceneIndexService's resumable rebuild and GrepSearchService page
    // through this without any filter, and rely on page boundaries staying
    // stable across separate query executions (e.g. resuming a rebuild
    // after a restart) to never skip or duplicate an opus.
    @Query("select div from TeiDiv div where div.parent is null order by div.id")
    Page<TeiDiv> findOpera(Pageable pageable);

    @Query("select div from TeiDiv div where div.parent is null")
    @EntityGraph(attributePaths = {"teiFile", "teiFile.authors"})
    List<TeiDiv> findAllOpera();

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

    @RestResource(exported = false)
    @Override
    <S extends TeiDiv> S save(S entity);

    @RestResource(exported = false)
    @Override
    void delete(TeiDiv entity);
}
