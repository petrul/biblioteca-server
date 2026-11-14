# Slow first page views: diagnosis and plan (2026-11-14)

Production pages were slow on their first view. The worst cases were large
works: the Psalms index took 13.4 s, the New Testament 12.1 s, one Old
Testament book 15.5 s. A second view took 0.1–0.2 s. This plan fixes the
cause and the things that made it expensive, on the 0.9.17-SNAPSHOT
(Fathomless Fountain).

## What was wrong

1. **An import looped every minute, wiping every cache each time.**
   - **The loop:** `fr/ebooks_libres_et_gratuits/pline_le_jeune_lettres.tei.xml`
     failed every minute since at least 2026-11-12 18:30 (2,646 attempts in
     three days). `reimportFresherTeis` retries any file newer than its last
     successful import, so a file that always fails is retried forever.
   - **The wipe:** `TeiFileDbService.importTeiFile` starts by clearing **all**
     caches: the TOC, node and binary disk caches, and every Spring cache. It
     does so before the import, whether it then succeeds or not.

2. **Why it always failed: a shared, mutable static author.**
   - `Author.newFromOriginalNameInTeiFile` returns the **shared instance**
     from `Author.SPECIAL_AUTHORS` for the names in
     `special-authors.properties` ("Pline le Jeune" is one).
   - Saving it assigns that shared object an id: 993. When the import then
     rolls back, row 993 is gone, but the object keeps id 993.
   - Every later import merges an author with a non-existent id. Hibernate
     reports that as "Row was already updated or deleted by another
     transaction".
   - It is poisoned until the server restarts. Author 993 does not exist in
     the production database.

3. **Why a cache wipe costs seconds: the table of contents loads one row at a time.**
   - A decorated page (`DivController.requestForDecoratedHtml`) needs the
     work's TOC for its previous/next links. `Toc.parcurge_rec` walks every
     division with a lazy `getDbChildren()`: one SQL query per division.
   - For the Bible that is thousands of queries, 12–15 s, while holding a
     database connection.
   - Several requests for the same work build it in parallel (thread dumps
     showed up to five), and crawlers reach many works at once.
   - The Spring caches (`toc`, `node`, `opus`) share one Caffeine spec of
     **50 entries**, so even without the wipe, crawling evicts them quickly.

   - **Root cause of the per-query cost:** `_tei_elem` (172k rows in
     production) had no index on `parent_id` or `tei_file_id`, so every
     children lookup scanned the whole table.

4. **The reader asks for every chapter twice.**
   - `fetchRealChapterContent` requests both `.txt` and `.html`, so the
     server transforms the chapter twice.
   - The plain text is only used for read-aloud, the share excerpt, the
     sentence count and the "download as text" button. All of these can be
     derived from the HTML.

5. **The reader's crawler and SEO paths repeat requests.** A section page
   for a crawler (the text twin):
   - reads `/api/divs` twice;
   - fetches the decorated page;
   - then `buildPageSeo` reads `/api/divs` twice again;
   - and downloads the **whole division's TEI XML** (5.5 MB for the Old
     Testament) only to look for a first `<binaryObject>`. `/api/divs`
     already returns the division's `imageUrls`.

## Plan

### biblioteca-server

1. **Special authors are copies.** `Author.newFromOriginalNameInTeiFile`
   returns a fresh `Author` built from the special-author entry, never the
   shared instance. A test reproduces the poisoning: a special author's
   import fails and rolls back, then the next import succeeds.
2. **A failing file does not loop.** `reimportFresherTeis` remembers a
   failure per (filename, file modification time). A failed file is retried
   when the file changes, or after a back-off (1 h, doubling, at most 24 h),
   not every minute. The failure is logged once, with its cause. It is
   visible in the importer's status: the admin list of failed imports
   (`GET /api/admin/teirepos/failures`).
3. **Evict only what an import changes, and only once it has succeeded.**
   - `importTeiFile` no longer clears everything up front. After a
     successful commit it evicts what belongs to that file: the old work's
     TOC (disk and Spring), the `opus` entry for that author/work, the
     `hit_text` entries (cleared whole: short-lived and small), and the
     parsed-file cache (`TeiElem.forgetParsedFile`).
   - `node` and TOC entries are keyed by database id, and a re-import
     creates new ids, so the old entries can no longer be hit. They are
     deleted for the old ids, and never served.
   - The "destroy all and reimport all" admin action still clears everything.
4. **The TOC in one query.** One query loads every division of the work
   (`TeiDiv` by `teiFile`) with its parent id. The tree is built in memory
   with siblings ordered by id (the order `dbChildren` already uses) and
   walked depth-first. The result is the same `Toc` as before; a test
   compares the old and new orders on a nested work.
   - Cached TOC divisions are used detached (`isLeaf()`, their DTOs, their
     paragraphs), so their children are initialised at build time, as the old
     walk did, in batches of 100 (`@BatchSize` on `TeiElem.dbChildren`):
     tens of queries for the Bible instead of thousands.
   - **Indexes** on `_tei_elem(parent_id)` and `_tei_elem(tei_file_id)`:
     Flyway `V14__Index_tei_elem_parent_and_file` creates them on existing
     databases, and the entity declares them under the same names for a
     fresh one (ddl-auto creates the table after Flyway runs). Measured on the dev profile (database on srv2):
     a 10-division work went from 11 s to 16 ms, a 57-division one from 41 s
     to well under a second, and the largest dev work (Orlando Furioso,
     4,878 divisions) builds in 2.8 s.
   - The new repository queries are `@RestResource(exported = false)`: not
     public `/api/drest` searches, and the generated clients'
     `executeSearchTeidivGetN` names stay unchanged.
5. **One build per work at a time.** Concurrent requests for the same
   missing TOC wait for a single build (a per-work in-flight future); they do
   not each build their own.
6. **A work's TOC is made at import.** The import builds and stores the new
   work's TOC, so the first visitor never pays for it.
7. **Cache sizes per cache instead of one 50-entry spec.**
   - `toc` 500, since the disk cache stays the backing store;
   - `node` 5,000;
   - `opus` 5,000, now keyed to evict correctly;
   - `hit_text` unchanged at 5,000.
8. **Cache check API for the nestjs worker** (internal token, like
   `/api/internal/enrichment`):
   - `GET /api/internal/caches/toc` lists the works whose TOC is missing
     from the disk cache;
   - `POST /api/internal/caches/toc/{opusId}` builds one.

### biblioteca-nestjs

9. **A `cache` module alongside the enrichment modules.** It runs in the
   daily sweep and on demand through `POST /api/enrichment/run`. It asks the
   server which works lack a TOC and warms them one at a time, with a pause,
   so a sweep never competes with readers. It logs what it built and what
   failed, like the other modules. The server's client is regenerated with
   `rake gen-client`.

### biblioteca-reader

10. **One request per chapter.** The plain text is derived from the HTML in
    the browser, following `tei2text.xsl`: block elements as line breaks,
    a note's text inline in brackets, illustrations as `[IMAGE].`. The `.txt` request is gone; the
    "download as text" button keeps using the derived text.
11. **No repeated calls on crawler and SEO pages.**
    - The text twin passes the divisions it already read to `buildPageSeo`,
      and skips the illustration lookup (it sets the cover as the image
      anyway).
    - `/api/divs`'s `imageUrls` turned out to be enrichment art, not the
      chapter's illustrations, so the SEO illustration lookup still reads
      XML, but only a leaf chapter's own. A section's preview is its work
      cover. This runs on every SPA reader page, not only for crawlers.

### Also fixed on the way

12. **The server's `.txt` of a division ran words together** ("LORD
    BACONA GARDENGOD almighty", "CHAPTER 1CHAPTER 2"). `tei2text.xsl` only
    separated a block from a following sibling, and had no template for
    `subtitle`, `item`, `cell`, `closer` and others. It now has a document
    mode for whole divisions: the `.txt` export, DAV and MCP. A
    paragraph-level element's text, and its `.txt` export, is unchanged
    byte for byte (checked on 274,017 paragraphs and 5,899 divisions from
    150 corpus files). Lucene, grep, quote fragments and the vectors'
    `text_sha256` are computed from that text, so nothing is re-indexed or
    re-embedded.
13. **A manual re-import without the leading `/`** (`fr/x.tei.xml`) missed
    the existing `/fr/x.tei.xml` import and imported the file a second
    time. Names are now normalised; a successful manual re-import also ends
    the sweep's back-off for that file.

The reader does not need `.txt`: it derives a chapter's plain text from the
HTML (step 10). `.txt` remains a public export (the TXT download in the
work page's DAV links, crawlers, MCP).

## Verification

- **Unit tests:** in each repo, for each change above. The server's import
  tests run on H2, as `RelocationReimportTest` does.
- **The server's integration suite:** on the `ci` profile (`rake test`).
- **Before and after, on the dev profile:**
  - the first view of the Bible's New Testament and Psalms pages (cold TOC);
  - the number of SQL statements for a TOC build;
  - a failing import retried only on back-off;
  - a successful import that leaves other works' caches alone.
- **Production:** with the next release (this is the snapshot), following
  the house release protocol and its test plan. Until then, restarting
  biblioteca-server stops the Pliny loop: the poisoned static author is
  reset. The import may still fail for its original reason, which will then
  be logged.
