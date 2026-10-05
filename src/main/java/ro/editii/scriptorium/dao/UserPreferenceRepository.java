package ro.editii.scriptorium.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.model.UserPreference;

import java.util.Optional;

@RepositoryRestResource(exported = false)
@Repository
public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {
    Optional<UserPreference> findByUsername(String username);
}
