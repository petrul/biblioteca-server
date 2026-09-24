package ro.editii.scriptorium.search.lucene;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.model.Languages;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.search.LuceneHit;
import ro.editii.scriptorium.service.ControllerTool;
import ro.editii.scriptorium.service.DivService;
import ro.editii.scriptorium.tei.TeiResourceNotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Full-text search over the corpus, at paragraph granularity (same grain as
 * the Milvus vector index's tb_paras_* collections - see VectorConfig) since
 * that's the unit a reader actually wants a hit to point at.
 *
 * Body text isn't stored relationally (see TeiElem.getNode()), so building
 * the index means re-deriving each paragraph's text the same way ann() does
 * for a single element: TeiElem -> toElemInfo() -> ControllerTool's XSLT
 * transform. Unlike Milvus (which stores no text, only vectors, and
 * resolves content separately at query time via ContentResolver), Lucene
 * stores the text itself, so a search hit needs no second resolution step.
 *
 * A full rebuild (rebuildIndex(), CREATE mode) still re-derives text for
 * the whole corpus - that part's unavoidable - but is no longer all-or-
 * nothing: buildIndex() commits (and refreshes the searcher) after every
 * page of opera rather than once at the end, so the index fills in
 * progressively as it builds instead of staying empty until 100% done,
 * and a commit's user data records exactly which page comes next
 * (COMMIT_DATA_NEXT_PAGE/COMMIT_DATA_REBUILD_COMPLETE). If the process is
 * killed mid-build, autoBuildIndexOnStartup reads that back and resumes
 * with resumeIndex() (CREATE_OR_APPEND) from wherever it left off, instead
 * of redoing the whole corpus from page 0 every time - see buildIndex's
 * own doc comment for why re-walking the in-flight page after a crash can
 * never duplicate documents.
 *
 * A single opus, though, gets a genuinely incremental update - see
 * reindexOpus(), called after every TEI (re)import (AdminService), and
 * removeOpus(), called after a source file disappears from the repo
 * (AdminService.pruneRemovedTeis). Both keep their slow/fast parts split
 * the same way reindexOpus always has (see its own doc comment) so a big
 * rebuild and a stream of small per-opus updates from ongoing imports
 * don't contend with each other or block search availability for
 * anything.
 *
 * The index also now builds itself automatically on startup if it isn't
 * fully built yet (autoBuildIndexOnStartup, lucene.autoindex.enabled) -
 * on a background, minimum-priority thread so a slow first build never
 * blocks app startup or competes hard with the actual application, e.g.
 * on a dev machine.
 */
@Service
@Log4j2
public class LuceneIndexService {

    public static final String FIELD_URL = "url";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_HEAD = "head";

    static final int OPERA_PAGE_SIZE = 20; // package-private: LuceneIndexServiceResumeTest references it
    private static final int SEARCH_CONTENT_BOOST = 1;
    private static final int SEARCH_HEAD_BOOST = 3;

    // Lucene commit user-data keys (IndexWriter.setLiveCommitData /
    // SegmentInfos.readLatestCommit().getUserData()) - how buildIndex
    // checkpoints rebuild progress durably, so autoBuildIndexOnStartup can
    // tell "never built", "interrupted partway through, resume at page N"
    // and "fully built, nothing to do" apart. Carried forward unchanged by
    // reindexOpus's/removeOpus's own commits (Lucene defaults a commit's
    // user data to the previous commit's unless explicitly overwritten),
    // so neither touches or needs to know about these.
    static final String COMMIT_DATA_NEXT_PAGE = "lucene-rebuild-next-page"; // package-private: referenced by tests
    static final String COMMIT_DATA_REBUILD_COMPLETE = "lucene-rebuild-complete"; // package-private: referenced by tests

    private final Path indexDir;
    private final Directory directory;
    private final Analyzer analyzer;
    private final TeiDivRepository teiDivRepository;
    private final DivService divService;
    private final ControllerTool controllerTool;
    private final boolean autoIndexEnabled;

    // Guards the MAIN index's writer - both rebuildIndex() and
    // reindexOpus()'s brief merge step take this, so the two can never
    // open competing IndexWriters on the same Directory at once (Lucene
    // allows only one at a time). reindexOpus() only holds it for the
    // fast addIndexes() merge, not for deriving the opus's text.
    private final Object rebuildLock = new Object();
    private volatile SearcherManager searcherManager;

    public LuceneIndexService(@Value("${lucene.index.dir}") String indexDir,
                               @Value("${lucene.autoindex.enabled}") boolean autoIndexEnabled,
                               TeiDivRepository teiDivRepository,
                               DivService divService,
                               ControllerTool controllerTool) throws IOException {
        // Expand a leading ~ to the user's home (same as CacheConf/AppConfig):
        // lucene.index.dir defaults to ${cache.dir}/lucene-index, which
        // inherits the pass store's WORK_DIR value - and that is allowed to
        // use the conventional leading-~/ form (e.g. ~/.biblioteca), which
        // must never reach Path.of as a literal tilde-named directory.
        this.indexDir = Path.of(Util.replaceTilde(indexDir));
        Files.createDirectories(this.indexDir);
        this.directory = FSDirectory.open(this.indexDir);
        // Language-aware: FIELD_CONTENT/FIELD_HEAD (always populated) use
        // TextbaseAnalyzer; a document whose TeiFile.language has a Lucene
        // built-in analyzer (see LuceneAnalyzers) also gets extra
        // "content_<lang>"/"head_<lang>" fields analyzed with real
        // per-language stemming/stopwords, for documents imported after
        // language detection was added (see TeiFileDbService).
        this.analyzer = LuceneAnalyzers.perFieldAnalyzer(new TextbaseAnalyzer(), FIELD_CONTENT, FIELD_HEAD);
        this.teiDivRepository = teiDivRepository;
        this.divService = divService;
        this.controllerTool = controllerTool;
        this.autoIndexEnabled = autoIndexEnabled;

        if (DirectoryReader.indexExists(this.directory)) {
            this.searcherManager = new SearcherManager(this.directory, null);
            log.info("Lucene full-text index found at {} - /api/search/lucene is available.", this.indexDir);
        } else {
            log.warn("No Lucene index at {} yet - /api/search/lucene will return empty results until "
                    + "it's built (see autoBuildIndexOnStartup/lucene.autoindex.enabled, or "
                    + "POST /api/admin/lucene/reindex to trigger one manually).", this.indexDir);
        }
    }

    /**
     * Kicks off a build in the background right after startup if the index
     * isn't fully built yet - either missing entirely (first run) or left
     * incomplete by a rebuild that got interrupted before finishing (see
     * COMMIT_DATA_REBUILD_COMPLETE) - runs on its own minimum-priority
     * thread so a slow build never blocks app startup or competes hard with
     * the actual application for CPU, which matters most on a dev machine.
     * Disabled entirely via lucene.autoindex.enabled for anyone who'd
     * rather trigger it manually (POST /api/admin/lucene/reindex) on their
     * own schedule.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void autoBuildIndexOnStartup() {
        if (!this.autoIndexEnabled) {
            log.info("Lucene lucene.autoindex.enabled=false - not auto-building the index on startup.");
            return;
        }
        final Map<String, String> commitData = readCommitData();
        if (Boolean.parseBoolean(commitData.getOrDefault(COMMIT_DATA_REBUILD_COMPLETE, "false"))) {
            return;
        }
        final int resumeFromPage = Integer.parseInt(commitData.getOrDefault(COMMIT_DATA_NEXT_PAGE, "0"));

        final Thread thread = new Thread(() -> {
            try {
                if (resumeFromPage == 0) {
                    log.info("Lucene index missing - auto-building it now in the background (low priority).");
                    this.rebuildIndex();
                } else {
                    log.info("Lucene index build was interrupted at page {} - resuming in the background "
                            + "(low priority) instead of starting over.", resumeFromPage);
                    this.resumeIndex(resumeFromPage);
                }
            } catch (Exception e) {
                log.error("Background (re)build of the Lucene index failed - /api/search/lucene stays "
                        + "incomplete until POST /api/admin/lucene/reindex is tried manually.", e);
            }
        }, "lucene-auto");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public boolean isAvailable() {
        return this.searcherManager != null;
    }

    /**
     * Full rebuild, always starting completely fresh (OpenMode.CREATE) -
     * the manual "I want every paragraph re-derived from scratch" entry
     * point (POST /api/admin/lucene/reindex), and what a genuinely first-
     * ever build (no index at all yet) also uses. See buildIndex for what
     * actually makes this resumable if interrupted.
     */
    public int rebuildIndex() {
        return this.buildIndex(0, IndexWriterConfig.OpenMode.CREATE);
    }

    /**
     * Continues an interrupted rebuildIndex() from wherever its last
     * periodic checkpoint commit left off, instead of redoing the whole
     * corpus - see autoBuildIndexOnStartup, the only caller. Uses
     * CREATE_OR_APPEND (never CREATE) since the whole point is to build on
     * top of what's already durably committed, not wipe it.
     */
    int resumeIndex(int fromPage) { // package-private: LuceneIndexServiceResumeTest calls this directly to test resumption without spinning a background thread
        return this.buildIndex(fromPage, IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
    }

    /**
     * Walks every opus (root TeiDiv, see TeiDivRepository.findOpera) from
     * startPage onward and indexes each of its paragraphs
     * (DivService.getParagraphs already walks the whole work's Toc,
     * sub-chapters included). One bad paragraph (e.g. an XSLT transform
     * failure) is logged and skipped rather than aborting the whole build -
     * same reasoning as the per-message isolation used elsewhere for
     * batch/streaming work. An opus whose TEI source has vanished from the
     * repos entirely (stale rows still in the DB, awaiting the prune) is
     * skipped the same way: indexing nothing for it is the correct
     * outcome, and one ghost must not leave every other opus unindexed.
     *
     * Commits (and refreshes the searcher) after every page rather than
     * once at the very end, for two reasons: the index becomes
     * progressively searchable as pages land instead of staying empty
     * until 100% done, and an interruption (container restart, OOM) loses
     * at most one page's worth of re-derivation - the commit's user data
     * (COMMIT_DATA_NEXT_PAGE/COMMIT_DATA_REBUILD_COMPLETE) records exactly
     * where to resume, read back by autoBuildIndexOnStartup. A page's own
     * adds are never durable until its commit() call, so re-walking the
     * same page after a crash (nothing since the last commit survives a
     * hard kill) can never duplicate documents.
     */
    private int buildIndex(int startPage, IndexWriterConfig.OpenMode openMode) {
        synchronized (this.rebuildLock) {
            final StopWatch watch = new StopWatch();
            watch.start();
            int indexed = 0;
            int skipped = 0;
            int skippedOpera = 0;

            try (IndexWriter writer = new IndexWriter(this.directory,
                    new IndexWriterConfig(this.analyzer).setOpenMode(openMode))) {

                int pageNr = startPage;
                Page<TeiDiv> opera;
                do {
                    opera = this.teiDivRepository.findOpera(PageRequest.of(pageNr, OPERA_PAGE_SIZE));
                    for (TeiDiv opus : opera) {
                        final List<TeiElem> paragraphs;
                        try {
                            paragraphs = this.divService.getParagraphs(opus);
                        } catch (TeiResourceNotFoundException e) {
                            // The opus's TEI source is gone from the repos
                            // but its rows are still in the DB - the prune
                            // (TeiImportScheduler) removes them on a later
                            // cycle, and indexing nothing for it is the
                            // correct outcome until then. One such ghost
                            // must not abort the whole build (observed in
                            // prod: a single stale row kept the startup
                            // auto-build from ever completing, leaving
                            // /api/search/lucene incomplete indefinitely).
                            skippedOpera++;
                            log.warn("Skipping opus while building the Lucene index - its source is missing ({}): {}",
                                    safeCompletePath(opus), e.getMessage());
                            continue;
                        }
                        for (TeiElem para : paragraphs) {
                            try {
                                final Document doc = toDocument(para);
                                if (doc != null) {
                                    writer.addDocument(doc);
                                    indexed++;
                                }
                            } catch (Exception e) {
                                skipped++;
                                log.warn("Skipping paragraph while building the Lucene index ({}): {}",
                                        safeCompletePath(para), e.getMessage());
                            }
                        }
                    }
                    pageNr++;

                    writer.setLiveCommitData(Map.of(
                            COMMIT_DATA_NEXT_PAGE, String.valueOf(pageNr),
                            COMMIT_DATA_REBUILD_COMPLETE, "false"
                    ).entrySet());
                    writer.commit();
                    this.refreshSearcherAfterCommit();
                } while (opera.hasNext());

                writer.setLiveCommitData(Map.of(
                        COMMIT_DATA_NEXT_PAGE, "0",
                        COMMIT_DATA_REBUILD_COMPLETE, "true"
                ).entrySet());
                writer.commit();
            } catch (IOException e) {
                throw new RuntimeException("Failed to build the Lucene index at " + this.indexDir, e);
            }

            this.refreshSearcherAfterCommit();

            watch.stop();
            log.info("Built Lucene index from page {}: {} paragraphs indexed, {} skipped, {} opera skipped (missing source), took {}",
                    startPage, indexed, skipped, skippedOpera, watch);
            return indexed;
        }
    }

    private void refreshSearcherAfterCommit() {
        try {
            if (this.searcherManager == null) {
                this.searcherManager = new SearcherManager(this.directory, null);
            } else {
                this.searcherManager.maybeRefreshBlocking();
            }
        } catch (IOException e) {
            throw new RuntimeException("Lucene index committed but failed to open/refresh a searcher for it", e);
        }
    }

    /** Empty (not missing keys) if the directory has no commit at all yet - a genuinely fresh index. */
    Map<String, String> readCommitData() { // package-private: LuceneIndexServiceResumeTest asserts on this directly
        try {
            return SegmentInfos.readLatestCommit(this.directory).getUserData();
        } catch (IndexNotFoundException e) {
            return Map.of();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read the Lucene index's commit metadata at " + this.indexDir, e);
        }
    }

    /**
     * Purges just this one opus's documents (exact root match plus every
     * descendant div beneath it, same URL-prefix matching as reindexOpus's
     * own delete step) - called from AdminService.pruneRemovedTeis once a
     * source file has disappeared from the repo and there's no fresh
     * content to add back, unlike reindexOpus which always follows its
     * delete with an addIndexes() of freshly-derived documents.
     */
    public void removeOpus(String opusPath) {
        synchronized (this.rebuildLock) {
            try (IndexWriter writer = new IndexWriter(this.directory,
                    new IndexWriterConfig(this.analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))) {
                writer.deleteDocuments(
                        new TermQuery(new Term(FIELD_URL, opusPath)),
                        new PrefixQuery(new Term(FIELD_URL, opusPath + "/")));
                writer.commit();
            } catch (IOException e) {
                throw new RuntimeException("Failed to remove opus " + opusPath + " from the Lucene index", e);
            }

            try {
                if (this.searcherManager != null) {
                    this.searcherManager.maybeRefreshBlocking();
                }
            } catch (IOException e) {
                throw new RuntimeException("Removed opus " + opusPath + " but failed to refresh the Lucene searcher", e);
            }
            log.info("Removed opus {} from the Lucene index", opusPath);
        }
    }

    /**
     * Incrementally reindexes just this one opus - called after every TEI
     * (re)import (see AdminService), not just a manual full rebuild. The
     * slow part (deriving every paragraph's text via toDocument/XSLT)
     * happens in a throwaway temp Directory/IndexWriter of its own, which
     * never touches the main index or its rebuildLock - only the fast
     * part (drop this opus's previous documents, merge the freshly-built
     * segments in via addIndexes()) briefly opens the main index. Search
     * keeps serving the previous version of this opus (and every other
     * opus, and a concurrent full rebuild if one happens to be running)
     * right up until that brief merge commits.
     */
    public int reindexOpus(TeiDiv opus) {
        final StopWatch watch = new StopWatch();
        watch.start();
        final String opusPath = opus.getCompletePath();
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(this.indexDir.getParent(), "lucene-opus-");
            int indexed = 0;
            try (Directory tempDirectory = FSDirectory.open(tempDir)) {
                // tempWriter's own try-with-resources closes (and releases
                // its write lock on tempDirectory) BEFORE addIndexes()
                // below - addIndexes() needs to take that same lock itself
                // to read the source directory, so it fails outright if
                // tempWriter is still holding it open at that point.
                try (IndexWriter tempWriter = new IndexWriter(tempDirectory, new IndexWriterConfig(this.analyzer))) {
                    for (TeiElem para : this.divService.getParagraphs(opus)) {
                        try {
                            final Document doc = toDocument(para);
                            if (doc != null) {
                                tempWriter.addDocument(doc);
                                indexed++;
                            }
                        } catch (Exception e) {
                            log.warn("Skipping paragraph while reindexing opus {} ({}): {}",
                                    opusPath, safeCompletePath(para), e.getMessage());
                        }
                    }
                    tempWriter.commit();
                }

                synchronized (this.rebuildLock) {
                    try (IndexWriter writer = new IndexWriter(this.directory,
                            new IndexWriterConfig(this.analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))) {
                        // Exact match for the opus's own root paragraph (a
                        // work with no chapter breakdown at all is its own
                        // single readable/indexed div - same "leaf === root"
                        // case the reader's own router.ts had to account
                        // for), plus every descendant div's URL beneath it.
                        // A bare prefix on opusPath alone would wrongly also
                        // match an unrelated opus whose path happens to
                        // start with this one's (e.g. "seneca/de-vita" vs
                        // "seneca/de-vita-longa").
                        writer.deleteDocuments(
                                new TermQuery(new Term(FIELD_URL, opusPath)),
                                new PrefixQuery(new Term(FIELD_URL, opusPath + "/")));
                        writer.addIndexes(tempDirectory);
                        writer.commit();
                    }

                    if (this.searcherManager == null) {
                        this.searcherManager = new SearcherManager(this.directory, null);
                    } else {
                        this.searcherManager.maybeRefreshBlocking();
                    }
                }
            }

            watch.stop();
            log.info("Reindexed opus {}: {} paragraphs, took {}", opusPath, indexed, watch);
            return indexed;
        } catch (IOException e) {
            throw new RuntimeException("Failed to reindex opus " + opusPath, e);
        } finally {
            deleteTempDirQuietly(tempDir);
        }
    }

    private static void deleteTempDirQuietly(Path tempDir) {
        if (tempDir == null) return;
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up temp Lucene dir {}: {}", tempDir, e.getMessage());
        }
    }

    private Document toDocument(TeiElem para) {
        final String text = this.controllerTool.teiElemToString(para.toElemInfo());
        if (text == null || text.isBlank())
            return null;

        // Kept as the original (accented) text, not folded - it's both what
        // gets stored/returned verbatim to callers (LuceneHit.content) and
        // what the per-language field's real stemmer wants to see; the
        // generic FIELD_CONTENT/FIELD_HEAD fields fold diacritics on their
        // own at the token level (TextbaseAnalyzer's ASCIIFoldingFilter),
        // so a diacritics-free query still matches through those.
        final String head = para.getDiv().getHead();
        final Languages language = documentLanguage(para);

        final Document doc = new Document();
        doc.add(new StringField(FIELD_URL, para.getCompletePath(), Field.Store.YES));
        doc.add(new TextField(FIELD_CONTENT, text, Field.Store.YES));
        addPerLanguageField(doc, FIELD_CONTENT, text, language);
        if (head != null && !head.isBlank()) {
            doc.add(new TextField(FIELD_HEAD, head, Field.Store.YES));
            addPerLanguageField(doc, FIELD_HEAD, head, language);
        }
        return doc;
    }

    private void addPerLanguageField(Document doc, String baseField, String value, Languages language) {
        final String fieldName = LuceneAnalyzers.perLanguageFieldName(baseField, language);
        if (fieldName != null)
            // Not stored - it's the same text already stored on baseField,
            // this field only exists to be searched with a better analyzer.
            doc.add(new TextField(fieldName, value, Field.Store.NO));
    }

    private static Languages documentLanguage(TeiElem para) {
        try {
            return para.getOpus().getTeiFile().getLanguage();
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeCompletePath(TeiElem elem) {
        try {
            return elem.getCompletePath();
        } catch (Exception e) {
            return "xpath=" + elem.getXpath();
        }
    }

    /**
     * Every base field, plus every language-specific variant Lucene has an
     * analyzer for (see LuceneAnalyzers) - a query doesn't know in advance
     * which language(s) it'll match, so it's cheaper to always search the
     * full set (Lucene skips fields with no matching terms fast) than to
     * try to detect the query's own language first.
     */
    private static Map<String, Float> searchFieldsAndBoosts() {
        final Map<String, Float> fields = new LinkedHashMap<>();
        fields.put(FIELD_CONTENT, (float) SEARCH_CONTENT_BOOST);
        fields.put(FIELD_HEAD, (float) SEARCH_HEAD_BOOST);
        for (Languages language : Languages.values()) {
            final String contentField = LuceneAnalyzers.perLanguageFieldName(FIELD_CONTENT, language);
            if (contentField != null) {
                fields.put(contentField, (float) SEARCH_CONTENT_BOOST);
                fields.put(LuceneAnalyzers.perLanguageFieldName(FIELD_HEAD, language), (float) SEARCH_HEAD_BOOST);
            }
        }
        return fields;
    }

    public List<LuceneHit> search(String q, int limit) {
        if (!this.isAvailable())
            return List.of();

        final Query query;
        try {
            final Map<String, Float> fieldsAndBoosts = searchFieldsAndBoosts();
            final var parser = new MultiFieldQueryParser(
                    fieldsAndBoosts.keySet().toArray(new String[0]),
                    this.analyzer,
                    fieldsAndBoosts);
            // Escaped so a user's free-text query is always treated as plain
            // words (like /divHeads or /authors, plain "contains" matching),
            // never as Lucene query syntax they didn't ask to opt into. Not
            // folded here - each target field's own analyzer decides
            // whether to fold diacritics (see toDocument/LuceneAnalyzers).
            query = parser.parse(QueryParser.escape(q));
        } catch (ParseException e) {
            log.warn("Could not parse Lucene query [{}]: {}", q, e.getMessage());
            return List.of();
        }

        try {
            final IndexSearcher searcher = this.searcherManager.acquire();
            try {
                final TopDocs topDocs = searcher.search(query, limit);
                final List<LuceneHit> hits = new ArrayList<>(topDocs.scoreDocs.length);
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    final Document doc = searcher.storedFields().document(scoreDoc.doc);
                    hits.add(new LuceneHit(
                            doc.get(FIELD_URL),
                            scoreDoc.score,
                            doc.get(FIELD_CONTENT),
                            doc.get(FIELD_HEAD)));
                }
                return hits;
            } finally {
                this.searcherManager.release(searcher);
            }
        } catch (IOException e) {
            log.warn("Lucene search failed for [{}]: {}", q, e.getMessage());
            return List.of();
        }
    }
}
