package ro.editii.scriptorium.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.model.AppUser;
import ro.editii.scriptorium.model.ReadingProgress;
import ro.editii.scriptorium.model.TeiDiv;

import java.util.List;
import java.util.Optional;

@RepositoryRestResource(exported = false)
@Repository
public interface ReadingProgressRepository extends JpaRepository<ReadingProgress, Long> {
    Optional<ReadingProgress> findByUserAndOpus(AppUser user, TeiDiv opus);

    List<ReadingProgress> findByUser(AppUser user);
}
