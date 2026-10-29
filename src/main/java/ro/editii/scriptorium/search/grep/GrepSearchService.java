package ro.editii.scriptorium.search.grep;

import ro.editii.scriptorium.ResourceLimits;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.time.StopWatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.search.GrepHit;
import ro.editii.scriptorium.service.ControllerTool;
import ro.editii.scriptorium.service.DivService;

import java.util.ArrayList;
import java.util.List;

/**
 * The "shallow" search: a plain case-insensitive literal substring scan
 * over the corpus, computed live on every call - no index, no stemming, no
 * relevance ranking, no diacritics folding (typed diacritics must match
 * exactly, like the real `grep` command). Unlike /api/search/lucene, this
 * always reflects the current corpus with no rebuild step, at the cost of
 * scanning (and re-deriving text for - see LuceneIndexService's doc comment
 * for why that's the expensive part) paragraphs on every request instead of
 * once at index-build time.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class GrepSearchService {

    private static final int OPERA_PAGE_SIZE = 20;

    // A query that matches nothing (or very little) would otherwise have to
    // scan/derive text for the entire corpus on every call - bounded so one
    // such request can't turn into an unbounded, ever-slower full-corpus
    // walk as the corpus grows. Results may be incomplete once this is hit
    // (logged, not silently swallowed).
    private static final int MAX_PARAGRAPHS_SCANNED = ResourceLimits.GREP_PARAGRAPHS_SCANNED_MAX;

    private final TeiDivRepository teiDivRepository;
    private final DivService divService;
    private final ControllerTool controllerTool;

    public List<GrepHit> search(String q, int limit) {
        final StopWatch watch = new StopWatch();
        watch.start();

        final String needle = q.toLowerCase();
        final List<GrepHit> hits = new ArrayList<>(Math.min(limit, 100));
        int scanned = 0;

        int pageNr = 0;
        Page<TeiDiv> opera;
        scan:
        do {
            opera = this.teiDivRepository.findOpera(PageRequest.of(pageNr, OPERA_PAGE_SIZE));
            for (TeiDiv opus : opera) {
                for (TeiElem para : this.divService.getParagraphs(opus)) {
                    if (scanned++ >= MAX_PARAGRAPHS_SCANNED) {
                        log.warn("grep search [{}] hit the {}-paragraph scan bound with only {} results found - "
                                + "results may be incomplete.", q, MAX_PARAGRAPHS_SCANNED, hits.size());
                        break scan;
                    }

                    final String text;
                    try {
                        text = this.controllerTool.teiElemToString(para.toElemInfo());
                    } catch (Exception e) {
                        continue;
                    }
                    if (text != null && text.toLowerCase().contains(needle)) {
                        hits.add(new GrepHit(para.getCompletePath(), text));
                        if (hits.size() >= limit)
                            break scan;
                    }
                }
            }
            pageNr++;
        } while (opera.hasNext());

        watch.stop();
        log.info("grep search [{}]: {} hits, {} paragraphs scanned, took {}", q, hits.size(), scanned, watch);
        return hits;
    }
}
