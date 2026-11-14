package ro.editii.scriptorium.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.service.DivService;

import java.util.List;

/**
 * What an import changes in the caches, and nothing more. An import used to
 * clear every cache - the TOC, node and binary disk caches and all Spring
 * caches - before it even started, so a TEI that kept failing emptied them
 * every minute (docs/caching-plan.md). Now, once the import has committed:
 *
 * - the caches keyed by path are cleared, since its paths now name new rows:
 *   the path-to-element ids, the works by author/work name, the search hits'
 *   text;
 * - the reimported work's old TOC is deleted (caches keyed by element id
 *   cannot serve the old rows: a reimport creates new ids);
 * - the new work's TOC is built, so its first reader does not wait for it.
 *
 * A failed import changes nothing: its transaction rolled back, the caches
 * still describe what the database holds.
 */
@Component @Log4j2 @RequiredArgsConstructor
public class ImportCacheMaintenance {

    /** The Spring caches keyed by path or name, which a reimport makes stale. */
    static final List<String> PATH_KEYED = List.of("tei_elem_id_by_path", "opus", CacheConf.CACHE_HIT_TEXT);

    final CacheManager cacheManager;
    final DivService divService;

    /** Call inside the import's transaction: the work happens after it commits, and not at all if it rolls back. */
    public void afterImportCommits(String teiFilename, List<Long> oldOpusIds, List<Long> newOpusIds) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            this.afterImport(teiFilename, oldOpusIds, newOpusIds);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ImportCacheMaintenance.this.afterImport(teiFilename, oldOpusIds, newOpusIds);
            }
        });
    }

    void afterImport(String teiFilename, List<Long> oldOpusIds, List<Long> newOpusIds) {
        TeiElem.forgetParsedFile(teiFilename);
        for (String name : PATH_KEYED) {
            final var cache = this.cacheManager.getCache(name);
            if (cache != null) cache.clear();
        }
        oldOpusIds.forEach(this.divService::forgetToc);
        for (Long opusId : newOpusIds) {
            try {
                this.divService.getToc(opusId);
            } catch (RuntimeException e) {
                // the first reader builds it instead
                log.warn("{}: imported, but the TOC of work {} could not be built: {}", teiFilename, opusId, e.toString());
            }
        }
    }
}
