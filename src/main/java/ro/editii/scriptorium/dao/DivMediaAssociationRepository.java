package ro.editii.scriptorium.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.rest.core.annotation.RestResource;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.media.DivMediaAssociation;
@RepositoryRestResource(exported = false)
@Repository
public interface DivMediaAssociationRepository extends JpaRepository<DivMediaAssociation, Long> {
    boolean existsByDivPathAndMediaRefRole(String divPath, String role);

    boolean existsByDivPathAndMediaRefUrl(String divPath, String url);

    @RestResource(exported = false)
    @Override
    <S extends DivMediaAssociation> S save(S entity);

    @RestResource(exported = false)
    @Override
    void delete(DivMediaAssociation entity);
}
