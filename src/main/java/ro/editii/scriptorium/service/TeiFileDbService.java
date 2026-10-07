package ro.editii.scriptorium.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.IOUtils;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.editii.scriptorium.TextbaseConfig;
import ro.editii.scriptorium.cache.DiskCaches;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.dao.TeiOpusRepository;
import ro.editii.scriptorium.dao.TeiElemRepository;
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher;
import ro.editii.scriptorium.model.*;
import ro.editii.scriptorium.tei.AuthorStrIdComputer;
import ro.editii.scriptorium.tei.TeifileParser;
import ro.editii.scriptorium.tei.TeiFileAlreadyImportedException;
import ro.editii.scriptorium.tei.TeiRepo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * this is a service over {@link TeiFileRepository},
 * so over the database table that stores the {@link TeiFile} object,
 * not over the file-based {@link TeiRepo}.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class TeiFileDbService {

    final TeiFileRepository teiFileRepository;
    final AuthorRepository authorRepository;
    final TeiDivRepository teiDivRepository;
    final TeiOpusRepository teiOpusRepository;
    final TeiRepo teiRepo;
    final AuthorStrIdComputer authorStrIdComputer;
    final CacheManager cacheManager;
    final DiskCaches allDiskCaches;
    final TextbaseEventsPublisher eventsPublisher;
    final TextbaseConfig textbaseConfig;
    final TeifileParser teifileParser;
    final LanguageDetectionService languageDetectionService;
    final TeiElemRepository teiElemRepository;

//
//    ParseTeiFileIntoDb newParser(String filename, InputStream is, Languages langHint) {
//        File file = this.teiRepo.getFile(filename);
//        try {
//            return new ParseTeiFileIntoDb(filename, is, file.toURI().toURL(), langHint
//                    authorRepository,
//                    teiFileRepository,
//                    teiDivRepository,
//                    authorStrIdComputer,
//                    langHint,
//                    eventsPublisher,
//                    textbaseConfig);
//        } catch (MalformedURLException e) {
//            throw new RuntimeException(e);
//        }
//    }

    @Transactional
    public void deleteTeiFile(String teiFilename) {
            final Optional<TeiFile> optionalTeiFile = this.teiFileRepository.getByFilename(teiFilename);

            if (optionalTeiFile.isPresent())
                this.deleteTeiFile(optionalTeiFile.get());
    }

    protected void deleteTeiFile(TeiFile dbTeiFile) {
        final List<Author> authors = dbTeiFile.getAuthors();
        // Catalog reads visit authors before their TEI files/elements. Taking
        // author write locks only after deleting a file inverted that order:
        // the reader held author S and waited for file S, while this writer
        // held file X and waited for author X. Acquire author locks first,
        // consistently by ID for files with multiple authors.
        authors.stream().map(Author::getId).distinct().sorted()
                .forEach(authorRepository::lockForFileDeletion);

        // Hibernate deletes the author join rows before the file itself.
        // Without this early file lock, a catalog count can hold file S while
        // waiting for those join rows, and our delete holds join X while
        // waiting for file X. Reserve the file before any deletion is queued.
        teiFileRepository.lockForDeletion(dbTeiFile.getId());

        final List<TeiDiv> opuses = this.teiDivRepository.getOperaForTeiFileId(dbTeiFile.getId());

        for (TeiDiv op: opuses) {
            this.delete_rec(op);
        }
        this.teiFileRepository.delete(dbTeiFile);

        for (Author author : authors) {
            // if author has no attached teifiles, delete the author too
            final List<TeiFile> teiFiles = this.authorRepository.getTeiFiles(author.getId());

            if (teiFiles.size() == 0)
                this.authorRepository.deleteById(author.getId());
        }
    }

    protected void delete_rec(TeiElem elem) {
        final List<TeiElem> children = elem.getDbChildren();

        if (children != null) {
            for (TeiElem child : children)
                this.delete_rec(child);
        }

        if (elem instanceof TeiDiv)
            this.teiOpusRepository.deleteByTeiDivId(elem.getId());
        // The single-table inheritance tree also contains paragraphs, notes,
        // heads, etc. Delete all element types through the base repository.
        this.teiElemRepository.delete(elem);
    }

    /**
     * Deletes an elem tree whose TeiFile row is already gone - the true
     * FK-orphan case (see AdminService.pruneOrphanedElems): the same
     * recursive walk deleteTeiFile uses, minus the TeiFile/Author cleanup
     * that has nothing left to clean. The root must itself be an orphan
     * (dangling/absent tei_file_id) - callers ensure that; this method
     * never touches rows that still belong to a live TeiFile.
     */
    @Transactional
    public void deleteOrphanedElems(TeiDiv root) {
        this.delete_rec(root);
    }

    @Transactional
    public void importTeiFile(String teiFilename, boolean forceReimport) throws TeiFileAlreadyImportedException {
        // invalidate caches
        this.allDiskCaches.deleteAll();
        this.evictAllCaches();

        final String content;
        try (InputStream streamForFile = this.teiRepo.getStreamForName(teiFilename)) {
            content = IOUtils.toString(streamForFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + teiFilename, e);
        }

        // Detected from the document's own text (see LanguageDetectionService) -
        // falls back to the old directory-path guess only if detection itself
        // can't confidently place it (e.g. too short, or a language this app
        // doesn't model - see Languages).
        final Languages langHint = this.languageDetectionService.detect(content)
                .orElseGet(() -> {
                    final Languages pathHint = this.teiRepo.getLanguageHint(teiFilename);
                    log.info("Could not detect a language for {} from its content - falling back to path-based hint ({})",
                            teiFilename, pathHint);
                    return pathHint;
                });

        final Optional<TeiFile> optionalTeiFile = this.teiFileRepository.getByFilename(teiFilename);
        if (optionalTeiFile.isPresent()) {
            if (forceReimport)
                this.deleteTeiFile(teiFilename);
            else
                return;
        }

        this.teifileParser.parse(teiFilename, content, langHint);

        // Not threaded through TeifileParser.parse's own overload chain
        // (unlike langHint, which was already a parameter there) - that
        // chain is also called directly by tests/CLI tooling without a real
        // TeiRepo behind them, so a required repoName param would ripple
        // out further than this one production import path warrants.
        // Re-fetching and setting it here instead.
        this.teiFileRepository.getByFilename(teiFilename).ifPresent(teiFile -> {
            teiFile.setRepoName(this.teiRepo.getRepoNameForFile(teiFilename));
            this.teiFileRepository.save(teiFile);
        });

        this.evictAllCaches();
    }

    public void evictAllCaches() {
        cacheManager.getCacheNames().stream()
                .forEach(cacheName -> cacheManager.getCache(cacheName).clear());
    }
}
