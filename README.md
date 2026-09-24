# Biblioteca Server

A structured digital library that serves classical and philosophical texts
— encoded in TEI P5 XML — as an addressable text database. Every author, work, chapter, paragraph, and even arbitrary character range has its own stable URL, all the way down to a single quotable sentence.

## What it does

- Serves the corpus over plain, crawlable HTTP: `GET /{author}/{work}/{...chapter-path}`,
  in HTML (site-chrome or bare), plain text, TEI XML, or a JSON AST — same
  content, four formats, content-negotiated or suffix-selected (`.txt`,
  `.xml`, `.json`, `.html`).
- Lets you cite or embed a permanent link to *any* selection of text, down
  to a character range inside one paragraph — not just a whole chapter (see
  [Fragment quotes](#fragment-quotes--getquote)).
- Gives you three different ways to search the corpus — a live literal
  scan, a real per-language full-text index, and embedding-based semantic
  similarity — all behind one response shape (see [Search](#search)).
- Exposes the whole corpus as a read-only, mountable DAV filesystem, so
  any DAV-capable client (or `curl -X PROPFIND`) can browse/export it
  without hitting the REST API at all (see [DAV export](#read-only-dav-export)).
- Lets real, signed-in readers keep personal collections (favorites,
  reading lists) via Google Sign-In or plain username/password.
- Publishes its own OpenAPI spec (`/api/docs.html`, `/api/docs`), so any
  client — human or generated — can discover every endpoint without
  reading this file.

## Why it's useful

If you want to link to, embed, or programmatically fetch a *specific
passage* of a classical text — not "the book," not even "the chapter," but
the exact sentence someone is quoting — most digital libraries can't give
you a stable URL for that. Textbase can, at every granularity from a whole
work down to a character offset, and it can render that selection as a
clean, shareable "quote card" with no site chrome, ready to embed in an
`<iframe>` elsewhere.

## Quickstart

Prerequisites: Java 25, a reachable MySQL instance, and at least one
configured local or Git-backed repository containing TEI XML (or source
documents such as Markdown or FODT for a later conversion step — see
[TEI processing](#tei-processing)). Milvus, Ollama/sentence-transformers, and
Kafka are needed for the full feature set (vector search, async vectorization)
but aren't required just to boot and browse the corpus — see [Search](#search)
for what degrades gracefully without them.

Repositories are configured in one required list, `TEI_REPOS`, with
comma-separated entries in the form `url|basepath|filespec`:

```bash
TEI_REPOS=/corpus/tei,/corpus/other,https://github.com/petrul/universal-literature-tei/
```

There is no implicit local repository. Each local entry initializes its own
repository, and its optional `basepath` is resolved beneath that directory.

For Docker deployments, `TEI_REPOS` uses host paths for local entries. The
deployment wrapper mounts each existing local directory and translates its
path for the container; Git entries remain unchanged.

Plain paths and `file:` URLs are local repositories; SSH, Git, HTTP, and
HTTPS URLs are cloned read-only with the system `git` command. Git checkouts
are persistent under `WORK_DIR/git-repos/<sha256-of-url>` and retain their
work area for future on-demand Markdown-to-TEI conversion.
An entry containing only a URL means the repository root and defaults to
`**/*.tei.xml`; `url|basepath` supplies only a subpath, and
`url|basepath|filespec` supplies both.

For example, these entries all use the same repository interface:

```bash
# Local repository; default recursive TEI XML selection.
/corpus/tei

# Git repository at its root; default recursive **/*.tei.xml selection.
https://github.com/petrul/universal-literature-tei/

# Git repository, restricted to a subdirectory and Markdown sources.
https://github.com/petrul/romcorpus|md|**/*.md
```

`WORK_DIR` is the persistent application work area. Its `cache/` subdirectory
stores the normal application caches, while Git checkouts are kept separately
under `git-repos/<sha256-of-url>`. On startup an existing checkout is updated
with `git pull --ff-only`; a missing checkout is created with `git clone`.
The Git command must be installed and usable non-interactively by the server
process. Repository discovery feeds the normal import/indexing pipeline, so
local and Git-backed sources are treated uniformly after checkout.

```bash
git clone <this repo>
cd biblioteca-server
cp .env.example .env.dev   # then fill in your MySQL/TEI-repo/etc values
./gradlew bootRun -x test  # -Pdev is the default profile; add -Pci/-Pprod for others
```

To run the development profile against the integration dependencies while
testing a Git-backed repository:

```bash
WORK_DIR=~/.biblioteca-work \
TEI_REPOS=https://github.com/petrul/universal-literature-tei/ \
./gradlew -Pdev bootRun -x test
```

Open `http://localhost:8080` — the site itself is server-rendered and
crawlable. The REST API is documented at `http://localhost:8080/api/docs.html`.
Modern browsers are sent to the reader app automatically; append `?noredirect` to
the root URL to explicitly serve the older Spring MVC/Thymeleaf index instead:
`http://localhost:8080/?noredirect`. Server-rendered navigation links preserve
this parameter, including the TEXTBASE title link and book navigation, so the
legacy UI can be inspected without being redirected to the reader app.

Config is layered as Spring profiles (`application-<profile>.properties` in
`src/main/resources/`, selected via `-Pdev`/`-Pci`/`-Pprod`/`-Pair`/`-Pint`
or `SPRING_PROFILES_ACTIVE`), with a `.env.<profile>` file (gitignored)
supplying the environment variables those properties files reference.
Gradle itself loads it — `build.gradle`'s `ProcessForkOptions` hook reads
`.env.${profile}` before any `bootRun`/`test` task runs and fills in any
variable not already present in the real process environment (CI/Vault
values always win) — so this works whether you invoke Gradle directly or
via `rake` (a thin wrapper around the same Gradle tasks, with no env
logic of its own). `.env.example` documents every variable that needs a
value.

## Environment variables

The canonical environment variable names expected by the application are:

| Variable | Purpose |
| --- | --- |
| `MILVUS_URL` | Milvus URL, including port |
| `EMBEDDER_URL` | Ollama/embedder URL, including port |
| `TEI_REPOS` | Comma-separated local or Git-backed TEI repository specifications |
| `KAFKA_BROKERS` | Kafka broker address or comma-separated broker addresses |
| `MYSQL_URL` | JDBC URL containing the database host, port, name, user, and password |
| `WORK_DIR` | Persistent application work directory; the application uses its `cache/` subdirectory for caches |
| `BIBLIOTECA_EXTERNAL_URL` | Public/base URL advertised by the application |
| `MLVCOL_TB_PARAS_BGE_M3` | Milvus collection for BGE-M3 vectors |
| `MLVCOL_TB_PARAS_ALL_MPNET_BASE_V2` | Milvus collection for all-mpnet-base-v2 vectors |
| `MLVCOL_TB_PARAS_QWEN3_EMBEDDING_4B` | Milvus collection for Qwen3 vectors |
| `MLVCOL_TEXTBASE_PARAS_STS_ALL_MINILM_L6_V2` | Milvus collection for STS vectors |
| `GOOGLE_OAUTH_CLIENT_ID` | Optional Google OAuth client ID |

`TEI_REPOS` can point directly to the local corpus build directory, such as
`/home/petru/work/scriptorium-masters/build/`.

## Running tests

```bash
./gradlew -Pci unittest      # network-free: no real Milvus/embedder needed
./gradlew test               # unit + external integration tests, CI profile by default
./gradlew -Pci integrationTest  # only the tests tagged external
```

`rake test` delegates to `./gradlew test`; `rake ci` runs that plus a full
build and Docker publish. CI values (TeamCity/Vault) are injected as real
environment variables, which always take precedence over `.env.<profile>`
— the file is only a local-developer fallback.

## Docker

```bash
./gradlew docker            # build editii/biblioteca-server:<version> locally
./gradlew docker-publish    # also push to the mini.local:5000 registry
```

---

## Architecture

A Spring Boot 4.1 / Gradle application, Java 25, package root `ro.editii.scriptorium`.

### REST / web layer

`.rest`, `.web` — the addressable book/chapter/paragraph URLs, the
basic-auth-protected Admin API (`/api/admin/*`), and a Relocation table
(HTTP redirects for moved URLs) exposed via `spring-data-rest` at
`/api/drest/`. Documented with springdoc-openapi (`/api/docs.html`,
JSON at `/api/docs`, YAML at `/api/docs.yaml` — also checked into the repo
as `textbase-swagger-api.json`); the integration suite parses the live
YAML and verifies representative paths, responses, and internal references.

`GET /{author}/{opus}/{...path}` (optionally suffixed `.txt`/`.xml`/`.json`/`.html`,
or content-negotiated via `Accept`; no suffix returns the decorated,
site-chrome HTML reader page — `DivController`) fetches one fragment, from
the whole work down to a single sub-chapter. It defaults to the fragment's
full text with every sub-chapter included, however deep. An optional
`?depth=N` caps that: `depth=0` returns only that div's own direct content
(no sub-chapters — the shallow, TOC-like view this endpoint used to always
return); `depth=N>0` keeps sub-chapters up to N levels below the requested
div. Depth counts the div's own addressable path segments, not raw TEI
`<div>` nesting — a structural wrapper div with no `<head>` of its own
(common in odt→TEI conversions) never got its own path segment at import
time and stays transparent here too.

### TEI processing

`.tei`, `.xslt`, `.toc` — parses/imports TEI XML sources (originals
authored as flat-ODT, piped odt → tei → web) using Saxon for XSLT/XML
rather than Xerces; builds tables of contents and per-fragment (down to
paragraph/word) addressing. `importTeiDivs` (a Gradle task) / `TeiDivImporterCli`
handle bulk import outside the web server.

A document's language is detected once at import time from its own text
(`LanguageDetectionService`, using the `lingua` library — no native/network
dependency) and stored on `TeiFile.language` and every `TeiDiv`/`TeiElem`
row, with the file's directory path (`TeiDirRepoImpl.getLanguageHint`) as a
fallback only when detection itself is inconclusive.

### Search

Three complementary modes, each its own `/api/search/*` endpoint,
returning the same `HitDto` shape (`type` distinguishes them):

- **Grep** (`.search.grep.GrepSearchService`, `GET /api/search/grep`) — a
  live, unindexed, case-insensitive literal substring scan over every
  paragraph, re-deriving text on every call. No stemming, no diacritics
  folding, no relevance ranking — always reflects the current corpus, at
  the cost of being the slowest option, bounded to the first 50,000
  paragraphs scanned per call.
- **Lucene** (`.search.lucene`, `GET /api/search/lucene`) — a real
  full-text index at paragraph granularity, rebuilt on demand via
  `POST /api/admin/lucene/reindex` (not automatically) into
  `lucene.index.dir`. Language-aware: each language gets its own analyzed
  field with Lucene's built-in stemmer/stopwords when one exists
  (`LuceneAnalyzers`), alongside an always-present generic, diacritics-folding
  field (`TextbaseAnalyzer`) that guarantees a diacritics-optional match
  regardless of language support.
- **Vector/deep** (`.vector`, `GET /api/search/milvus`, `GET /api/search/ann`) —
  embedding-based similarity search via Milvus. `VectorSearchAvailability`
  checks the embedder and the Milvus collection once at startup and
  disables vector search gracefully for the rest of that run if either is
  unreachable — `/api/search/milvus`/`/api/search/ann` return empty
  results instead of throwing.

Embeddings come from an `Embedder` (`.vector.Embedder`); the production
default (`@Primary`) is `qwen3EmbeddingEmbedder` (Qwen3-Embedding-4B via
Ollama, `ollama.host:ollama.port`), which replaced the earlier
sentence-transformers `all-mpnet-base-v2` embedder (`StsEmbedder`, still
available under its own bean name). The Milvus collection is named after
the active embedder (`tb_paras_qwen3_embedding_4b`) — switching embedders
means the corpus needs re-embedding into the new collection before search
against it works.

### Fragment quotes (`GET /quote`)

`.fragment` — a Fragment is an arbitrary selection within a `TeiDiv`'s
subtree, from a whole subchapter down to a single character, identified by
the div's own path plus a start/end pair in "dot number notation"
(`DotPath`, e.g. `2.1.15` = child 2, then child 1 of that, character 15 of
its text — the same 1-indexed addressing the URL path segments already
use). `FragmentResolutionService` resolves both points and walks
`DivService.getParagraphs()` to span one or several paragraphs, trimming
the first/last to their offsets.

```
GET /quote/{divPath}?start={dotPath}&end={dotPath}
```

Real example — Francis Bacon's "Of Gardens" opens with one of its most
quoted lines:

```bash
curl https://textbase.scriptorium.ro/quote/bacon/of_gardens?start=7.4.0&end=7.4.85
```

Renders as a standalone "quote card" — large low-opacity background quote
glyphs, Alegreya serif for the text, no site navigation — so it drops
cleanly into an `<iframe>` elsewhere. There's no UI yet to pick a quote by
selecting text on the page (dot-paths are meant to be produced by a future
"select this, get a link" feature); `FragmentResolutionServiceTest` and
`FragmentControllerITest` cover the mechanics in detail against a small
self-contained multi-language fixture set.

### Collections

`.collection`, `.model.DivCollection`/`DivCollectionItem`,
`/api/collections/*` — a named grouping of TeiDivs and/or Fragments, in
two kinds:

- `/api/collections/mine/*` — real, persisted, per-`AppUser` collections
  (owner-authenticated, full CRUD). Every user gets an auto-created,
  non-deletable `favorites` collection at registration.
- `/api/collections/system/*` — public, read-only, never persisted:
  by-language, by-author, and by-source-repo groupings computed on the fly.

### Accounts & sign-in

`.security`, `.model.AppUser` — real, persisted accounts, needed to own
Collections. Two sign-in paths:

- **Username/password**: `POST /api/users/register`, then Spring
  Security's default session-based `formLogin` (`POST /login`).
- **Google Sign-In / One Tap** (primary path): the widget POSTs a Google ID
  token to `POST /api/auth/google` (`GoogleAuthController`), verified
  server-side via Google's own `tokeninfo` endpoint (`GoogleTokenInfoVerifier`
  — no JWT/JOSE library needed) and find-or-creates an `AppUser` by the
  token's `sub` claim (`GoogleAuthService`), capturing the account's name,
  email, and avatar picture. Disabled until `google.oauth.client-id`
  (`GOOGLE_OAUTH_CLIENT_ID`) is set to a real Google Cloud OAuth client id
  — deliberately no guessed default, so an unset id disables the feature
  rather than silently accepting tokens meant for a different Google app.
  A Google-only account has no local password.

### Read-only DAV export

`.dav`, `/dav` — projects the corpus as a mountable
`language/author/work/chapter` virtual filesystem. `DavExportService`
resolves virtual paths and applies language/author filters and the
requested fragmentation depth; `DavExportRenderer` serializes terminal
fragments as text, JSON, TEI XML, or standalone XHTML; `DavExportController`
implements the read-only DAV surface (`OPTIONS`, `PROPFIND`, `GET`, `HEAD`
— mutation methods return `405`).

```bash
# Inspect the mount root and its immediate children.
curl -i -X PROPFIND -H 'Depth: 1' 'http://localhost:8080/dav?format=txt&fragmentation=1'
```

| Parameter | Values | Default | Meaning |
| --- | --- | --- | --- |
| `format` | `txt`, `json`, `xml`, `xhtml` | `txt` | File extension / serialization. `xml` is the original TEI fragment. |
| `fragmentation` | `1`, `1.1`, `1.1.1` | `1` | Deepest division exposed as a file: work, chapter, or subchapter. |
| `lang` | a two-letter code, e.g. `fr` | all | Restrict to works detected in that language. |
| `author` | a canonical author id, e.g. `alecsandri` | all | Restrict to that author's works. |

Don't use the query-param form as a DAV *mount* URL — several DAV clients
drop query strings on child `href`s. Mount the path-configured form
instead, which embeds the same filters directly in the path:

```
/dav/_export/{format}/{fragmentation}/{language-or-all}/{author-or-all}/
```

`PROPFIND` supports depths `0` and `1` only; `Depth: infinity` is rejected
(`403 propfind-finite-depth`) so one request can't materialize the whole
corpus. Fragmentation selects a frontier, not a requirement — a work that
ends at chapter level exposes that chapter as a file even when
`fragmentation` asks for subchapters; each terminal file still contains
its complete remaining subtree either way, so shallower branches never
lose text.

### Persistence, caching, messaging

`.dao`, `.model`, `.dto` — Spring Data JPA over MySQL, with a local
Caffeine + on-disk (`cache.dir`) cache layer (`.cache`). `.kafka` handles
scheduled/async work (`.scheduled`) — notably notifying `biblioteca-nestjs`
(a separate repo) of new/reimported opera so it can vectorize them.
`KafkaProps.java` has the real topic names (`biblioteca_*` prefix); this
service is the sole producer of every one of them.

The async/event-driven counterpart to the REST OpenAPI spec above is
`src/main/resources/static/asyncapi.yml` (served with this service and
checked into this repo) - documents each Kafka
topic's real message schema and, per topic, which service(s) actually
produce/consume it today (not aspirational - `opusReimportedTopic` and
`loginTopic` are both documented as currently having no consumer, since
that's the truth right now). Validate it with
`npx @asyncapi/cli validate asyncapi.yml`, or view it rendered at
[studio.asyncapi.com](https://studio.asyncapi.com) (paste the file's
contents in, or point it at this file's raw URL once this repo's readable
from wherever that's opened).

### Frontend

The public reader app and the admin app are separate, standalone repos
(`textbase-ionic-ui`, `textbase-admin-ui`), built and deployed
independently — this repo no longer builds or serves either SPA.
Server-rendered Thymeleaf templates (`teidiv.html`, etc.) still serve the
crawlable/lynx-browseable book pages directly from here, and a `textbase-reader`
repo consumes this server's REST API directly for a richer reading UI.

---

## Package map (orientation for developers & coding agents)

Every package lives under `ro.editii.scriptorium.*`:

| Package | What's there |
| --- | --- |
| `rest`, `web` | REST controllers, the DivController fragment endpoint, page-decoration model attributes |
| `service` | Core business logic: `AdminService`, `DivService` (path/child resolution), `LanguageDetectionService`, `DbSearchService` |
| `tei`, `xslt`, `toc` | TEI repo/import plumbing, Saxon-based XSLT tooling, table-of-contents building |
| `search`, `search.lucene`, `search.grep`, `vector` | The three search backends and the embedder abstraction |
| `fragment` | Dot-path fragment/quote resolution |
| `collection` | Personal and system collections |
| `dav` | The read-only DAV export surface |
| `security`, `security.google` | Auth (`AppUser`, Spring Security wiring, Google ID token verification) |
| `model`, `dto`, `dao` | JPA entities, API-facing DTOs, Spring Data repositories |
| `cache` | Caffeine + on-disk cache layer |
| `kafka`, `scheduled` | Async/scheduled work, including notifying downstream consumers of content changes |
| `client` | `TextbaseClient` — this server's own outbound HTTP client, e.g. for self-referential admin calls |
| `media` | Author/div media (image) associations |

For a concrete task, the fastest orientation path is usually: find the
REST controller in `rest`/`web` for the endpoint you care about, then
follow its injected service into the matching package above.

## Background

Originally named `scriptorium-repo`. The design goal from the start was a
text database addressable down to the paragraph, word, and letter — not
another ebook store. Source content is authored as flat-ODT (much easier
to edit than hand-written XML) and piped through an odt → TEI → web
pipeline at import time.
