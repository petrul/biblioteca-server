package ro.editii.scriptorium.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ro.editii.scriptorium.Globals;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.model.TeiFile;
import ro.editii.scriptorium.search.lucene.LuceneIndexService;
import ro.editii.scriptorium.tei.TeiFileAlreadyImportedException;
import ro.editii.scriptorium.tei.TeiRepo;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Optional;

@Service @Log4j2
@RequiredArgsConstructor
public class AdminService {

    final TeiRepo teiRepo;
    final TeiFileRepository teiFileRepository;
    final TeiDivRepository teiDivRepository;
    final TeiFileDbService teiFileDbService;
    final JdbcTemplate jdbcTemplate;
    final LuceneIndexService luceneIndexService;

    /**
     * Incrementally reindexes just the opus/opera this one TeiFile just
     * (re)imported - see LuceneIndexService.reindexOpus for why this is
     * cheap and doesn't block search or contend with a full rebuild.
     * Called after every successful importTeiFile(), not just a manual
     * full reindex - a re-imported book's stale Lucene entries would
     * otherwise linger (wrong content, or content for divs that no
     * longer exist) until someone remembers to trigger one by hand.
     */
    private void reindexLuceneForImportedFile(String filename) {
        try {
            this.teiFileRepository.getByFilename(filename).ifPresent(teiFile ->
                    this.teiDivRepository.getOperaForTeiFileId(teiFile.getId())
                            .forEach(this.luceneIndexService::reindexOpus));
        } catch (RuntimeException e) {
            // A Lucene hiccup must never abort or roll back the DB
            // import itself - same reasoning as one bad paragraph not
            // aborting a full rebuild (LuceneIndexService.rebuildIndex).
            // Worst case, this file's Lucene entries stay stale until
            // the next full reindex.
            log.error("Failed to incrementally reindex Lucene for {} - the TEI import itself still succeeded", filename, e);
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
                        this.reindexLuceneForImportedFile(filename);
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
                this.reindexLuceneForImportedFile(filename);
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
                        this.reindexLuceneForImportedFile(filename);
                    } catch (TeiFileAlreadyImportedException e) {
                        log.error(e.getMessage(), e);
                    }
                }
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
