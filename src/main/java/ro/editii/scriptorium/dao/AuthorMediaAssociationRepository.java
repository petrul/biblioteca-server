package ro.editii.scriptorium.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.rest.core.annotation.RestResource;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.media.AuthorMediaAssociation;

import java.util.List;

@RepositoryRestResource(exported = false)
@Repository
public interface AuthorMediaAssociationRepository extends JpaRepository<AuthorMediaAssociation, Long> {
    boolean existsByAuthorPathAndMediaRefRole(String authorPath, String role);

    boolean existsByAuthorPathAndMediaRefUrl(String authorPath, String url);

    List<AuthorMediaAssociation> findAllByAuthorPath(String authorPath);

    @RestResource(exported = false)
    @Override
    <S extends AuthorMediaAssociation> S save(S entity);

    @RestResource(exported = false)
    @Override
    void delete(AuthorMediaAssociation entity);
}
