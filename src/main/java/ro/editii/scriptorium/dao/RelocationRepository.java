package ro.editii.scriptorium.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.model.Relocation;

import java.util.Collection;
import java.util.List;

@RepositoryRestResource(exported = false)
@Repository
public interface RelocationRepository extends JpaRepository<Relocation, String> {

    /** Relocations that end on one of these paths - to repoint when those move again. */
    List<Relocation> findByNewPathIn(Collection<String> newPaths);
}
