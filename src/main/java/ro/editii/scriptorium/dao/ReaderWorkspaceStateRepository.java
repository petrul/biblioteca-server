package ro.editii.scriptorium.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.model.ReaderWorkspaceState;

import java.util.Optional;

@Repository
public interface ReaderWorkspaceStateRepository extends JpaRepository<ReaderWorkspaceState, Long> {
    Optional<ReaderWorkspaceState> findByOwnerUsername(String username);
}
