package ro.editii.scriptorium.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.rest.core.annotation.RestResource;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiFile;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@RepositoryRestResource(exported = false)
@Repository
public interface TeiFileRepository extends JpaRepository<TeiFile, Long> {
    List<TeiFile> findByFilename(String filename);

    Optional<TeiFile> getByFilename(String filename);

    /** Reserve the file row before Hibernate removes its many-to-many author links. */
    // SELECT FOR UPDATE uses Derby U locks; reserve a real X lock instead.
    @Modifying
    @Query(value = "update \"_tei_file\" set id = id where id = :id", nativeQuery = true)
    int lockForDeletion(@Param("id") long id);

    List<TeiFile> findByRepoName(String repoName);

    /** Just enough to know whether/when a filename was last imported - see FilenameAndTimestamp. */
    interface FilenameAndTimestamp {
        String getFilename();
        Timestamp getTimestamp();
    }

    /**
     * Backs reimportFresherTeis/reimportAllTeis's "is this file already
     * imported, and is the import stale" check - a full corpus repo listing
     * (thousands of files) used to pay one getByFilename query per file
     * (which, via TeiFile's eager authors association, was really a query
     * plus a join fetch per file). One query for the whole corpus instead,
     * projected down to just the two columns that decision actually needs.
     */
    @Query("select tf.filename as filename, tf.timestamp as timestamp from TeiFile tf")
    List<FilenameAndTimestamp> findAllFilenamesAndTimestamps();

    @Query("select distinct tf.repoName from TeiFile tf where tf.repoName is not null")
    List<String> findDistinctRepoNames();

    @Query("""
            select tf from TeiFile tf 
            join tf.authors a 
            where a.strId = ?1
            """)
    List<TeiFile> getTeiFilesForAuthorStrId(String strid);

    @RestResource(exported = false)
    @Override
    <S extends TeiFile> S save(S entity);

    @RestResource(exported = false)
    @Override
    void delete(TeiFile entity);
}
