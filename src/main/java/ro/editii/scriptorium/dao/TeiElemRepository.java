package ro.editii.scriptorium.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ro.editii.scriptorium.model.TeiElem;

@Repository
public interface TeiElemRepository extends JpaRepository<TeiElem, Long> {

    // save/delete used to be @RestResource(exported = false) here - DREST
    // writes were disabled while /api/drest/** was reachable from the
    // internet (see biblioteca-server/README.md). Now that it never is
    // (reader is the only bridge, biblioteca-nestjs the only other
    // caller, both on the trusted network), the default exported
    // save/delete apply.
}
