package ro.editii.scriptorium.dao;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.model.DivCollectionItem;

@RepositoryRestResource(exported = false)
@Repository
public interface DivCollectionItemRepository extends JpaRepository<DivCollectionItem, UUID> {
}
