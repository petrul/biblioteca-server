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
import ro.editii.scriptorium.service.AuthorMergeService;
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
    final AuthorMergeService authorMergeService;

    // 60s, not 15s: still fast enough for a live-editing feedback loop
    // (edit a TEI file, see it reflected shortly after) without polling
    // four times as often as actually useful - a full repo listing +
    // fresher-check on every tick, even after batching away its N+1
    // query (see AdminService.reimportFresherTeis), is still a full
    // corpus walk every cycle.
    @Scheduled(fixedRate = 60 * 1000)
    public void importTeis() {
        synchronized (Globals.IMPORT_TEIS_WORKING) {
            try {
                adminService.reimportFresherTeis(new NoWriter());
            } catch (RuntimeException e) {
                log.error("Scheduled reimport of fresher TEIs failed - will retry next cycle", e);
            }
        }
    }

    /**
     * Hourly sweep of TeiFiles whose source file vanished from the repos
     * (see AdminService.pruneRemovedTeis), with a first run a minute after
     * boot so a DB carrying stale rows is cleaned promptly. Its own
     * schedule rather than a rider on the 60s import cycle: the prune is a
     * quick DB pass but still a full corpus walk (teiRepo.list +
     * findAll), needlessly constant at 60s - same cadence reasoning as the
     * orphan-elem sweep below. Both share IMPORT_TEIS_WORKING with the
     * reimport, so a long import pass delays the prune (and vice versa)
     * by at most one cycle each.
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 1000)
    public void pruneRemovedTeis() {
        synchronized (Globals.IMPORT_TEIS_WORKING) {
            try {
                adminService.pruneRemovedTeis(new NoWriter());
            } catch (RuntimeException e) {
                log.error("Scheduled prune of removed TEIs failed - will retry next cycle", e);
            }
        }
    }

    /**
     * Hourly sweep of true FK-orphans (tei_elem rows whose tei_file row
     * vanished out-of-band - see AdminService.pruneOrphanedElems), with a
     * first run a minute after boot so a DB carrying orphans is cleaned
     * promptly. Rare by construction (raw SQL on prod is the only known
     * cause), so an hourly cadence - not the 60s import cycle, whose
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

    /**
     * Hourly sweep of author rows whose tei_file vanished out-of-band (see
     * AuthorMergeService.pruneOrphanedAuthors) - the per-file author cleanup
     * in TeiFileDbService.deleteTeiFile cannot reach a file-less author, so
     * this is the only thing that ever removes them. Own schedule, same
     * cadence reasoning as the two sweeps above: quick DB pass, rare by
     * construction (only out-of-band deletion or the fixed
     * TeiFileAlreadyImportedException hole produce such rows), and it shares
     * IMPORT_TEIS_WORKING with the reimport/prunes, so a long import pass
     * delays it (and vice versa) by at most one cycle each.
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 1000)
    public void pruneOrphanedAuthors() {
        synchronized (Globals.IMPORT_TEIS_WORKING) {
            try {
                this.authorMergeService.pruneOrphanedAuthors(new NoWriter());
            } catch (RuntimeException e) {
                log.error("Scheduled prune of orphaned authors failed - will retry next cycle", e);
            }
        }
    }
}

