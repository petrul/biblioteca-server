package ro.editii.scriptorium.embedding;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(path = "embeddingBatchStats", collectionResourceRel = "embeddingBatchStats")
public interface EmbeddingBatchStatRepository extends JpaRepository<EmbeddingBatchStat, Long> {

    Page<EmbeddingBatchStat> findAllByOrderByIdDesc(Pageable pageable);
}
