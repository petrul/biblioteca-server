# Search

Three complementary modes under `/api/search/*`, one `HitDto` response shape
(`type` distinguishes them):

- **Grep** — `GET /api/search/grep`: live, unindexed, case-insensitive literal
  substring scan over every paragraph. No stemming, no ranking; always current,
  slowest option, bounded to the first 50,000 paragraphs per call.
- **Lucene** — `GET /api/search/lucene`: real per-paragraph full-text index,
  rebuilt on demand via `POST /api/admin/lucene/reindex` into
  `lucene.index.dir`. Language-aware analyzed fields per language
  (`LuceneAnalyzers`) plus an always-present diacritics-folding generic field.
- **Vector/deep** — `GET /api/search/vector`, `GET /api/search/ann`:
  embedding-based similarity via the active vector store (qdrant by default).
  `VectorSearchAvailability` checks the embedder and store once at startup and
  disables vector search gracefully for the rest of the run if either is
  unreachable — these endpoints return empty results instead of throwing.

The production embedder is **bge-m3 via Ollama** (`@Primary`, 1024-dim,
multilingual); `qwen3-embedding:4b` (2560-dim) and the legacy
sentence-transformers `StsEmbedder` remain available as named beans. The
default collection is `biblioteca_paragraphs_bge_m3`, prefixed per environment
(`dev-`/`int-`); switching embedders means re-embedding the corpus into a new
collection before search against it works.

## Search data is precious: retention and reuse, manual-only drops

Embeddings and Lucene documents take **days** to recompute — assets, not
disposable indexes:

- **No automatic flow drops search content.** A reimported book is reindexed in
  place; a book removed from the repo keeps both its Lucene documents and its
  vectors (`AdminService.pruneRemovedTeis` prunes DB rows and emits
  `opusRemoved`; the vectorizer logs it and *retains* the vectors). The
  manual-only escape hatches are `POST /api/admin/lucene/reindex` and the
  vectorizer's `POST /api/vector-store/remove-opus` / `reset`.
- **Missing source requests clean up stale DB rows.** A content request returns
  404 and removes the missing TEI file's opera, descendants and orphaned authors
  using the same cascade as the scheduled prune. Cleanup requires the recorded
  original repository to be configured, enabled, ready and successfully listed,
  followed by a fresh physical absence check under the import lock. Unknown
  repository provenance or failed access preserves the rows for later cleanup.
  If an import/prune already owns the lock, the request returns 404 without
  waiting and leaves cleanup to the scheduled sweep. Waiting while the HTTP
  session retains a JDBC connection can otherwise exhaust the pool and prevent
  the lock-owning scheduler from making progress.
  External corpus rebuilds should publish replacements atomically; a temporary
  deletion in an otherwise readable repository is indistinguishable from a
  permanent removal. Lucene documents and vectors remain retained.
- **Stale hits are filtered, not deleted** — a removed book's urls 404 and the
  resolver drops null-content hits, so users never see dead links while the
  data stays recoverable.
- **Vectors are reused by sha256**: on reimport the vectorizer embeds only
  paragraphs without a stored vector and repoints unchanged paragraphs to
  renamed urls without touching embeddings.

Covered by `LuceneReindexRetentionPolicyTest`, `AdminServiceRetentionPolicyTest`
(server) and `vector_reuse.spec.ts` (vectorizer).
