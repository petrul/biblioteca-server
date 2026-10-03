package ro.editii.scriptorium.dao;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.TeiFile;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuthorRepository extends JpaRepository<Author, Long> {

    List<Author> findByStrId(String strId);
    Page<Author> findByStrIdContainingIgnoreCase(String strId, Pageable page);
    List<Author> findByOriginalNameInTeiFile(String originalName);

    Optional<Author> getByStrId(String strId);
    Optional<Author> getByOriginalNameInTeiFile(String originalName);

    @Query("select tf from TeiFile tf join tf.authors a where a.id = ?1")
    List<TeiFile> getTeiFiles(long authorId);

    Page<Author> findByLastNameContainingIgnoreCase(String excerpt, Pageable page);
    Page<Author> findByLastNameIgnoreCase(String excerpt, Pageable page);
    Page<Author> findByFirstNameContainingIgnoreCase(String excerpt, Pageable page);

    @Query("""
            select a from Author a
            where :q is null or :q = ''
               or lower(a.strId) like lower(concat('%', :q, '%'))
               or lower(a.firstName) like lower(concat('%', :q, '%'))
               or lower(a.lastName) like lower(concat('%', :q, '%'))
               or lower(a.displayName) like lower(concat('%', :q, '%'))
            order by a.lastName, a.firstName, a.strId
            """)
    Page<Author> findCatalogPage(@Param("q") String query, Pageable page);

    // save/delete used to be @RestResource(exported = false) here - DREST
    // writes were disabled while /api/drest/** was reachable from the
    // internet (see biblioteca-server/README.md). Now that it never is
    // (reader is the only bridge, biblioteca-nestjs the only other
    // caller, both on the trusted network), the default exported
    // save/delete apply.

}
