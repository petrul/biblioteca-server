package ro.editii.scriptorium.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.editii.scriptorium.Globals;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.dto.OpusRemovedDto;
import ro.editii.scriptorium.enrichment.EnrichmentService;
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiFile;
import ro.editii.scriptorium.search.lucene.LuceneIndexService;
import ro.editii.scriptorium.tei.TeiFileAlreadyImportedException;
import ro.editii.scriptorium.tei.TeiRepo;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service @Log4j2
@RequiredArgsConstructor
public class AdminService {

    final TeiRepo teiRepo;
    final TeiFileRepository teiFileRepository;
    final TeiDivRepository teiDivRepository;
    final TeiFileDbService teiFileDbService;
    final JdbcTemplate jdbcTemplate;
    final LuceneIndexService luceneIndexService;
    final EnrichmentService enrichmentService;
    final TextbaseEventsPublisher textbaseEventsPublisher;

    @Value("${lucene.incremental.enabled:true}")
    boolean incrementalLuceneEnabled = true;

    /**
     * Everything that should happen right after one TeiFile is
     * successfully (re)imported, besides the DB import itself - called
     * from all three reimport entry points below, never just a manual
     * full reindex:
     *
     * - Incrementally reindexes just the opus/opera this file contains
     *   (LuceneIndexService.reindexOpus) - a re-imported book's stale
     *   Lucene entries would otherwise linger (wrong content, or content
     *   for divs that no longer exist) until someone remembers to
     *   trigger a full rebuild by hand.
     * - Kicks off best-effort author bio / opus summary enrichment
     *   (EnrichmentService) - a no-op once either already has one, so
     *   this only ever actually does anything the first time a given
     *   author/opus is seen.
     *
     * Both are wrapped so a hiccup in either NEVER aborts or rolls back
     * the DB import itself - same reasoning as one bad paragraph not
     * aborting a full Lucene rebuild.
     */
    private void postImportHooks(String filename) {
        final Optional<TeiFile> optionalTeiFile = this.teiFileRepository.getByFilename(filename);
        if (optionalTeiFile.isEmpty()) return;
        final TeiFile teiFile = optionalTeiFile.get();

        final List<TeiDiv> opera = this.teiDivRepository.getOperaForTeiFileId(teiFile.getId());
        if (this.incrementalLuceneEnabled) {
        for (TeiDiv opus : opera) {
            try {
                this.luceneIndexService.reindexOpus(opus);
            } catch (RuntimeException e) {
                log.error("Failed to incrementally reindex Lucene for opus {} ({}) - the TEI import itself still succeeded",
                        opus.getCompletePath(), filename, e);
            }
        }
        }

        try {
            final List<Author> authors = teiFile.getAuthors();
            final List<String> workTitles = opera.stream().map(TeiDiv::getHead).filter(h -> h != null && !h.isBlank()).toList();
            for (Author author : authors) {
                this.enrichmentService.backfillNativeLanguageIfMissing(author, teiFile.getLanguage());
                this.enrichmentService.enrichAuthorAsync(author, workTitles, teiFile.getLanguage());
            }
            for (TeiDiv opus : opera) {
                this.enrichmentService.enrichOpusAsync(opus);
            }
        } catch (RuntimeException e) {
            log.error("Failed to kick off AI enrichment for {} - the TEI import itself still succeeded", filename, e);
        }
    }

    /**
     * Full rebuild - see LuceneIndexService for why this isn't incremental.
     * Synchronous, same as reimportAllTeis/reimportFresherTeis: whoever
     * calls the admin endpoint waits for it, rather than this service
     * inventing its own async job-tracking machinery for one caller.
     */
    public int reindexLucene() {
        return this.luceneIndexService.rebuildIndex();
    }

    public void reimportFresherTeis(Writer logActivity) {
        synchronized (Globals.IMPORT_TEIS_WORKING) {
            final List<String> filenames = teiRepo.list();

            for (String filename : filenames) {
                final File file = teiRepo.getFile(filename);
                final Optional<TeiFile> optionalTeiFile = this.teiFileRepository.getByFilename(filename);
                if (optionalTeiFile.isPresent()
                        && optionalTeiFile.get().getTimestamp().getTime() > file.lastModified()) {
                    // do nothing if already imported and file is not fresher than import
                    continue;
                } else {
                    if (optionalTeiFile.isPresent()) {
                        log.info("will delete existing import for {} ", filename);
                        writeLn(logActivity, "will delete existing import for " + filename);
                        this.teiFileDbService.deleteTeiFile(filename);
                    }

                    try {
                        log.info("will import {} ", filename);
                        writeLn(logActivity, "will delete existing import for " + filename);
                        this.teiFileDbService.importTeiFile(filename, true);
                        this.postImportHooks(filename);
                    } catch (TeiFileAlreadyImportedException e) {
                        log.error(e.getMessage(), e);
                    } catch (RuntimeException e) {
                        log.error("caught runtime exception logging but will continue with other files", e);
                    }
                }
            }
        }
    }

    public void reimportFile(String filename, Writer logActivity) {
        synchronized (Globals.IMPORT_TEIS_WORKING) {
            writeLn(logActivity,String.format("will now import %s ...", filename));
            File file = teiRepo.getFile(filename);
            Optional<TeiFile> optionalTeiFile = this.teiFileRepository.getByFilename(filename);

            if (optionalTeiFile.isPresent()) {
                log.info("will delete existing import for {} ", filename);
                writeLn(logActivity, "will delete existing import for " + filename);
                this.teiFileDbService.deleteTeiFile(filename);
            }

            try {
                log.info("will import {} ", filename);
                writeLn(logActivity, "will import " + filename);
                this.teiFileDbService.importTeiFile(filename, true);
                this.postImportHooks(filename);
            } catch (TeiFileAlreadyImportedException e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    public void reimportAllTeis(Writer logActivity) {
        synchronized (Globals.IMPORT_TEIS_WORKING) {
            writeLn(logActivity, "will now import ...");
            List<String> filenames = teiRepo.list();

            for (String filename : filenames) {
                File file = teiRepo.getFile(filename);
                Optional<TeiFile> optionalTeiFile = this.teiFileRepository.getByFilename(filename);
                if (optionalTeiFile.isPresent()
                        && optionalTeiFile.get().getTimestamp().getTime() > file.lastModified()) {
                    // do nothing if already imported and file is not fresher than import
                    continue;
                } else {
                    if (optionalTeiFile.isPresent()) {
                        log.info("will delete existing import for {} ", filename);
                        writeLn(logActivity, "will delete existing import for " + filename);
                        TeiFile teiFile = optionalTeiFile.get();
                        this.teiFileDbService.deleteTeiFile(filename);
                    }

                    try {
                        log.info("will import {} ", filename);
                        writeLn(logActivity, "will delete existing import for " + filename);
                        this.teiFileDbService.importTeiFile(filename, true);
                        this.postImportHooks(filename);
                    } catch (TeiFileAlreadyImportedException e) {
                        log.error(e.getMessage(), e);
                    }
                }
            }
        }
    }

    /**
     * Detects TeiFiles whose source file no longer exists in any configured
     * repo (removed from scriptorium-masters since it was last imported) -
     * something none of the reimport methods above ever check, since they
     * only ever walk files currently present in teiRepo.list() - and purges
     * everything derived from it: the DB rows themselves
     * (teiFileDbService.deleteTeiFile, which also drops now-orphaned
     * authors), this file's opera from the Lucene index
     * (LuceneIndexService.removeOpus), and a signalOpusRemoved event per
     * removed opus so textbase-nestjs can drop its matching Milvus vectors
     * too - the same "biblioteca-server is the sole source of truth, nothing
     * downstream keeps its own opinion" pattern signalNewOpusImported
     * already uses.
     *
     * Never runs as a side effect of a normal reimport - deliberately its
     * own entry point, called from TeiImportScheduler alongside
     * reimportFresherTeis (autoimport profile) and exposed for manual use
     * at POST /api/admin/teirepos/pruneRemoved.
     */
    public void pruneRemovedTeis(Writer logActivity) {
        synchronized (Globals.IMPORT_TEIS_WORKING) {
            final Set<String> filesOnDisk = new HashSet<>(this.teiRepo.list());
            final List<TeiFile> allTeiFiles = this.teiFileRepository.findAll();

            for (TeiFile teiFile : allTeiFiles) {
                if (filesOnDisk.contains(teiFile.getFilename())) {
                    continue;
                }

                final List<TeiDiv> opera = this.teiDivRepository.getOperaForTeiFileId(teiFile.getId());
                final List<String> opusPaths = opera.stream().map(TeiDiv::getCompletePath).toList();

                log.info("will prune removed TeiFile {} ({} opera)", teiFile.getFilename(), opusPaths.size());
                writeLn(logActivity, "will prune removed TeiFile " + teiFile.getFilename());

                // Per-file isolation: one failing file must not abort the
                // prune of every file after it in the same run (and, called
                // from the scheduler every 15s, forever after) - a single
                // bad row would otherwise keep all removed files' cleanup
                // from ever completing.
                try {
                    this.teiFileDbService.deleteTeiFile(teiFile.getFilename());
                } catch (RuntimeException e) {
                    log.error("Failed to prune removed TeiFile {} - continuing with the remaining files "
                            + "(it will be retried on the next prune)", teiFile.getFilename(), e);
                    continue;
                }

                for (String opusPath : opusPaths) {
                    try {
                        this.luceneIndexService.removeOpus(opusPath);
                    } catch (RuntimeException e) {
                        log.error("Failed to remove opus {} from the Lucene index after pruning {} - the DB "
                                + "prune itself still succeeded", opusPath, teiFile.getFilename(), e);
                    }
                    try {
                        this.textbaseEventsPublisher.signalOpusRemoved(OpusRemovedDto.builder().path(opusPath).build());
                    } catch (RuntimeException e) {
                        log.error("Failed to signal removal of opus {} - Milvus vectors for it may linger", opusPath, e);
                    }
                }
            }
        }
    }

    /**
     * Sweeps true FK-orphans: tei_elem rows whose tei_file row is gone
     * (dangling or NULL tei_file_id) - the residue of out-of-band
     * tei_file deletion (raw SQL on the DB), never of the normal
     * deleteTeiFile cascade. pruneRemovedTeis cannot see these (it walks
     * tei_file rows, which is precisely what is missing here); until the
     * Lucene build learned to skip them, one such orphan could also keep
     * the whole index from ever completing.
     *
     * Orphaned opera (root divs) are deleted through the same recursive
     * walk deleteTeiFile uses, per-item isolated; whatever orphaned rows
     * remain afterwards (a tree broken mid-level, non-div elems without
     * any root) has nothing navigating to it, so a bulk anti-join delete
     * finishes the job. Lucene documents and Milvus vectors for orphans
     * are deliberately not cleaned here: getCompletePath needs the (gone)
     * TeiFile, so their paths are unresolvable - the next full index
     * rebuild (which completes despite ghosts, see LuceneIndexService)
     * wipes the Lucene side, and vector hits for dead divs degrade to
     * 404s until then.
     */
    public void pruneOrphanedElems(Writer logActivity) {
        synchronized (Globals.IMPORT_TEIS_WORKING) {
            // queryForList(sql) (not the typed (sql, Class) overload):
            // deliberately the plainest overload - the typed one cannot be
            // stubbed from the Groovy test suite, where Mockito's
            // all-matchers rule meets Groovy's runtime overload dispatch
            // (matchers return null, and (sql, null) is ambiguous between
            // the Class and Object... overloads).
            final List<Long> orphanedOperaIds = this.jdbcTemplate.queryForList(
                            "SELECT d.id FROM " + Util.TEI_ELEM + " d"
                                            + " LEFT JOIN tei_file f ON f.id = d.tei_file_id"
                                            + " WHERE f.id IS NULL AND d.parent_id IS NULL AND d.name = 'div'")
                            .stream()
                            .map(row -> ((Number) row.get("id")).longValue())
                            .toList();

            int pruned = 0;
            for (final Long id : orphanedOperaIds) {
                final Optional<TeiDiv> opus = this.teiDivRepository.findById(id);
                if (opus.isEmpty())
                    continue;
                try {
                    this.teiFileDbService.deleteOrphanedElems(opus.get());
                    pruned++;
                    log.info("pruned orphaned opus id {} - its tei_file row is gone", id);
                    writeLn(logActivity, "pruned orphaned opus id " + id);
                } catch (RuntimeException e) {
                    log.error("Failed to prune orphaned opus {} - continuing with the rest (it will be retried next sweep)", id, e);
                }
            }

            // Leftovers: orphaned rows with no (reachable) root. Nothing
            // can navigate to these, so a bulk delete is safe and final.
            final int leftovers = this.jdbcTemplate.update(
                    "DELETE e FROM " + Util.TEI_ELEM + " e"
                            + " LEFT JOIN tei_file f ON f.id = e.tei_file_id"
                            + " WHERE f.id IS NULL");

            if (pruned > 0 || leftovers > 0) {
                log.info("Orphan sweep: {} opera trees and {} leftover elems removed", pruned, leftovers);
                writeLn(logActivity, "orphan sweep: " + pruned + " opera trees, " + leftovers + " leftover elems removed");
            }
        }
    }

    public void destroyAllExistingAndReimportAllTeis(Writer logActivity, boolean iUnderstandThatThisIsAPotentiallyDangerousOperation) {
        synchronized (Globals.IMPORT_TEIS_WORKING) {
            writeLn(logActivity, "will first destroy existing data...");

            this.jdbcTemplate.update("SET FOREIGN_KEY_CHECKS = 0");
            this.jdbcTemplate.update("truncate table tei_file_authors");
            this.jdbcTemplate.update("truncate table author");
            this.jdbcTemplate.update("truncate table " + Util.TEI_ELEM);
            this.jdbcTemplate.update("truncate table tei_file");
            this.jdbcTemplate.update("SET FOREIGN_KEY_CHECKS = 1");

            List<TeiFile> allExistingTeiFiles = this.teiFileRepository.findAll();
            writeLn(logActivity, "will first destroy existing...");
            for (TeiFile tf : allExistingTeiFiles) {
                writeLn(logActivity, "will destroy " + tf);
                this.teiFileDbService.deleteTeiFile(tf);
            }

            this.reimportAllTeis(logActivity);
        }

    }

    private void writeLn(Writer writer, String s) {
        try {
            writer.write(s + " <br/>\n");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
}
