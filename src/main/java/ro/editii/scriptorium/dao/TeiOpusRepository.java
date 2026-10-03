package ro.editii.scriptorium.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.model.TeiOpus;

import java.util.Optional;

@Repository
public interface TeiOpusRepository extends JpaRepository<TeiOpus, Long> {
    Optional<TeiOpus> findByTeiDivId(Long teiDivId);
    void deleteByTeiDivId(Long teiDivId);
}
