package ro.editii.scriptorium.embedding;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(path = "opusVectorizingStats", collectionResourceRel = "opusVectorizingStats")
public interface OpusVectorizingStatRepository extends JpaRepository<OpusVectorizingStat, Long> {

    Page<OpusVectorizingStat> findAllByOrderByIdDesc(Pageable pageable);
}
