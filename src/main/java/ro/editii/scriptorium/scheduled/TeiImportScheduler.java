package ro.editii.scriptorium.scheduled;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
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
@Log4j2
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
            // Independent try/catches: a failure in one half (a poison file
            // aborting reimportFresherTeis outside its per-file handler,
            // say) must not starve the other - a prune that never gets its
            // turn leaves stale rows for vanished corpus files forever.
            // The prune runs FIRST: it's a quick DB pass, while the reimport
            // can hold this lock for hours on a large corpus - running the
            // prune second meant stale rows (whose opera break the Lucene
            // auto-build, among other things) survived a whole import pass
            // before their cleanup even started.
            try {
                adminService.pruneRemovedTeis(new NoWriter());
            } catch (RuntimeException e) {
                log.error("Scheduled prune of removed TEIs failed - will retry next cycle", e);
            }
            try {
                adminService.reimportFresherTeis(new NoWriter());
            } catch (RuntimeException e) {
                log.error("Scheduled reimport of fresher TEIs failed - will retry next cycle", e);
            }
        }
    }

    /**
     * Hourly sweep of true FK-orphans (tei_elem rows whose tei_file row
     * vanished out-of-band - see AdminService.pruneOrphanedElems), with a
     * first run a minute after boot so a DB carrying orphans is cleaned
     * promptly. Rare by construction (raw SQL on prod is the only known
     * cause), so an hourly cadence - not the 15s import cycle, whose
     * anti-join over the whole elem table would be needlessly constant.
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 1000)
    public void pruneOrphanedElems() {
        synchronized (Globals.IMPORT_TEIS_WORKING) {
            try {
                adminService.pruneOrphanedElems(new NoWriter());
            } catch (RuntimeException e) {
                log.error("Scheduled prune of orphaned elems failed - will retry next cycle", e);
            }
        }
    }
}

