package ro.editii.scriptorium.scheduled;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.editii.scriptorium.Globals;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.service.AdminService;
import ro.editii.scriptorium.service.TeiFileDbService;
import ro.editii.scriptorium.tei.AuthorStrIdComputer;
import ro.editii.scriptorium.tei.TeiRepo;

@Component
@RequiredArgsConstructor
@Profile("autoimport")
public class TeiImportScheduler {

    final protected TeiFileRepository teiFileRepository;
    final protected AuthorRepository authorRepository;
    final protected TeiDivRepository teiDivRepository;
    final protected AuthorStrIdComputer authorStrIdComputer;
    final protected TeiFileDbService teiFileDbService;
    final TeiRepo teiRepo;
    final AdminService adminService;

    @Scheduled(fixedRate = 15 * 1000)
    public void importTeis() {
        synchronized (Globals.IMPORT_TEIS_WORKING) {
            adminService.reimportFresherTeis(new NoWriter());
            adminService.pruneRemovedTeis(new NoWriter());
        }
    }
}

