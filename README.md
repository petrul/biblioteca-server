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
you a stable URL for that. Biblioteca can, at every granularity from a whole
work down to a character offset, and it can render that selection as a
clean, shareable "quote card" with no site chrome, ready to embed in an
`<iframe>` elsewhere.

## Quickstart

Prerequisites: Java 25, a reachable Apache Derby Network Server instance
(`DB_URL`, see the environment variables table below - built by
`docker/derby`, see the Docker section), and at least one
configured local or Git-backed repository containing TEI XML (or source
documents such as Markdown or FODT for a later conversion step — see
[TEI processing](#tei-processing)). A vector store, Ollama/sentence-transformers, and
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
cp .env.example .env.dev   # then fill in your Derby/TEI-repo/etc values
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
| `VECTORSTORE_URL` | URL of the active vector store (qdrant or milvus), including port; `MILVUS_URL` is the legacy name still accepted as a fallback |
| `VECTOR_STORE` | Active vector store backend: `qdrant` (the default) or `milvus` |
| `VECTOR_COLLECTION` | Base vector collection name; defaults to `biblioteca_paragraphs_bge_m3` |
| `VECTOR_COLLECTION_PREFIX` | Prefix prepended to the collection name so non-prod environments can share the prod store (the dev/int profiles set `dev-`/`int-`); empty by default |
| `EMBEDDER_URL` | Ollama/embedder URL, including port |
| `TEI_REPOS` | Comma-separated local or Git-backed TEI repository specifications |
| `KAFKA_BROKERS` | Kafka broker address or comma-separated broker addresses |
| `DB_URL` | JDBC URL of the Derby Network Server instance (host, port, database name) with the credentials embedded as Derby connection attributes (`;user=...;password=...`) - engine-agnostic name on purpose, was `MYSQL_URL` |
| `WORK_DIR` | Persistent application work directory; the application uses its `cache/` subdirectory for caches |
| `BIBLIOTECA_EXTERNAL_URL` | Public/base URL advertised by the application |
| `GOOGLE_OAUTH_CLIENT_ID` | Optional Google OAuth client ID |

`TEI_REPOS` can point directly to the local corpus build directory, such as
`/home/petru/work/scriptorium-masters/build/`.

## Running tests

```bash
./gradlew -Pci unittest      # network-free: no real Milvus/embedder needed
./gradlew test               # unit + external integration tests, CI profile by default
./gradlew -Pci integrationTest  # only the tests tagged integration-test
```

The Rake tasks split the same way:

```bash
rake unittest[dev]       # unit and local component tests only
rake itest[dev]          # integration-test tagged tests only
rake test[dev]           # runs unittest and itest
```

`rake ci` uses the aggregate `rake test` behavior before the build and Docker
publish. CI values (TeamCity/Vault) are injected as real environment
variables, which always take precedence over `.env.<profile>`
— the file is only a local-developer fallback.

### Mocking in Groovy tests: prefer Groovy mocks over Mockito

Mockito is fragile from Groovy — `when(...)` stubbing with closures/maps,
argument confusion and the notorious `UnfinishedStubbingException` traps
(calling a mock-returning helper inside `thenReturn(...` corrupts the
stubbing state). Prefer, in this order:

- **Map-coercion fakes for interfaces and entities** —
  `[exists: { false }] as VectorCollection`; the map literal IS the fake.
- **Hand-rolled Groovy fake classes for class-typed collaborators** —
  a small inner class extending the real one (`super(null, null, ...)`)
  with the handful of methods the code under test actually calls,
  recording whatever the test needs to observe.
- Real instances wherever cheap (a temp-dir LuceneIndexService, a plain
  `JdbcTemplate`), which make the assertion behavioral rather than
  interaction-based.

Keep Mockito only where a Groovy alternative genuinely cannot express the
need; when you do use it from a Groovy test, resolve every mock to a
variable first and never build one inside another `when()/thenReturn()`
chain.

## Running bare-metal (dev)

Every service of the trio has a `rake run` task that starts it bare-metal with
its pass-store profile environment - this is the way to run the dev stack
outside containers (edit sources, restart in seconds):

```bash
# biblioteca-server: gradle bootRun on :8080. Profile = rake run[profile],
# PROFILE=<profile> or the machine's short hostname (the pass store's
# biblioteca/<hostname> section). SPRING_PROFILES_ACTIVE adds the
# autoimport scheduler (corpus import + per-opus Lucene reindex) and the
# dev profile (dev- prefixed vector collection):
SPRING_PROFILES_ACTIVE=autoimport,dev rake run

# biblioteca-nestjs: nest start on :3000 (dev pass profile) - the Kafka
# consumer and the vectorizer, reusing already-stored vectors by sha256:
rake run

# biblioteca-reader: tsx server.ts + Vite on :3333 (profile from the
# machine's hostname; the rakefile maps GOOGLE_OAUTH_CLIENT_ID to
# VITE_GOOGLE_CLIENT_ID so Google One-Tap works):
rake run
```

The services talk over the host network: server :8080 (BIBLIOTECA_EXTERNAL_URL),
nestjs :3000, reader :3333 (what Caddy's test.scriptorium.ro vhost proxies).
The database is the shared Derby Network Server from the deps stacks
(DB_URL/DB_USER/DB_PASSWORD from the same pass-store section); WORK_DIR is the
profile's own, so the Lucene index resumes across restarts.

## Docker

```bash
./gradlew docker            # build editii/biblioteca-server:<version> locally
./gradlew docker-publish    # also push to the mini.local:5000 registry
```

### Apache Derby Network Server image

`docker/derby/` builds a standalone [Apache Derby](https://db.apache.org/derby/) Network
Server container - the database this app expects (`DB_URL`, see the environment
variables table above). No official Derby image exists on Docker Hub, so this is the
buildable source of truth: anyone can stand up their own Derby server with

```bash
docker build -f docker/derby/Dockerfile -t biblioteca-derby docker/derby
docker run -d -p 1527:1527 -v <data-dir>:/var/lib/derby \
    -e DB_USER=... -e DB_PASSWORD=... biblioteca-derby
```

Credentials are required (the entrypoint refuses to start without them and configures
Derby's BUILTIN authentication so the one configured user is the only account that
can connect), and `DB_URL` then points at
`jdbc:derby://<host>:1527/<db>;create=true;user=<user>;password=<password>`.

The image is tagged with the Derby version it serves, kept in sync with the
`derbyclient` dependency in `build.gradle` on purpose - never pinned independently:

```bash
rake docker:derby:build     # build editii/biblioteca-derby:<derby-version> locally
rake docker:derby:publish   # also push to the mini.local:5000 registry
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

That `/api/docs` document is also the **source of two downstream generated
clients** — a REST-surface change here ripples into both, and each
consumer regenerates off a *running* server, so run it at the same commit
the API changed in:

- **biblioteca-nestjs**: `rake gen-client` → `src/biblioteca.api.ts`
  (swagger-typescript-api off the raw spec) — see that repo's README,
  "Regenerating the typed API clients".
- **biblioteca-reader**: `rake gen-client` →
  `src/generated/biblioteca-server-api.d.ts` (raw spec), plus the
  biblioteca-server-backed shapes inside this app's own public allowlist
  (`scripts/build-openapi-public.mjs` → `openapi-public.json`), which the
  browser client `src/services/serversideApi.ts` is typed against — see
  that repo's README, "Refresh the API clients".

Both repos check their generated clients in, so the resulting diff is the
reviewable record of the API change. A renamed/removed endpoint makes the
reader's allowlist build fail loudly — update `BIBLIOTECA_PATHS` there
when deliberately changing a consumed path.

The reader catalogue is intentionally page-oriented. `GET /api/authors/page`
and `GET /api/works` accept one-based `page` and `size` parameters and return
`items`, `totalItems`, and `totalPages`; both also accept `q`, while works
accepts `lang` (the server's two-letter language code). Consumers should use
these endpoints instead of downloading `/api/authors/` or the complete works
collection into browser memory.

### Runtime configuration (writable actuator)

The admin-only `GET /api/admin/runtime-config` endpoint exposes an
allow-listed view of operational settings that were initialized from the
profile's environment (for example `DB_URL`, `EMBEDDER_URL`, `KAFKA_BROKERS`,
and `TEI_REPOS`). `PUT /api/admin/runtime-config/{key}` with
`{"value":"..."}` changes a value in memory for the current JVM, and
`DELETE` resets it to the startup value. Environment-variable names and their
Spring property names are both accepted as keys.

The existing `GET /api/admin/config` shared-resource endpoint (consumed by
`biblioteca-nestjs`) reads its collection and paragraph settings from this
same runtime store, so a subsequent config fetch sees those changes.

Database URLs are marked sensitive and are masked unless an authenticated
administrator explicitly requests `?reveal=true`. Nothing is written back to
the pass store, `.env` files, or the database. Settings used to construct a
connection pool or client are reported with `restartRequired: true`: changing
them updates the runtime property source, but does not rebuild an already
running client.

Most `/api/drest/**` repositories exclude writes (`@RestResource(exported
= false)` on `save`/`delete`) or exclude themselves from DREST entirely
(child/association tables managed only through their parent's own
service logic, or entities like `AppUser`/`ReadingProgress` that need
real business logic - password handling, session scoping - generic CRUD
would bypass). `AuthorRepository`, `TeiElemRepository`, and
`TeiDivRepository` are the exception: full CRUD, including writes. That
was a real risk while `/api/drest/**` was reachable from the internet;
now that it never is (see "Public vs internal REST endpoints" below),
biblioteca-nestjs can use these three directly instead of needing a
dedicated write endpoint for everything it touches.

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

### Public vs internal REST endpoints

**Decision: this server's entire REST API (`/api/**`) and `/actuator/*`
are never reachable from the internet, no exceptions.** The only thing
Caddy exposes on `biblioteca.scriptorium.ro` is the reading surface
itself — `/`, `/app`, `/{author}/{opus}/{...path}` fragment URLs,
TOC/search pages, `/quote` — none of which live under `/api/`. Every
sibling service (`biblioteca-nestjs` included) reaches this API the same
way: over the trusted docker network directly (`http://server:8080` —
`BIBLIOTECA_EXTERNAL_URL`, never the public hostname). There is no
`/api/public/**` carve-out on this server and there does not need to be
one, because of the next point.

**biblioteca-reader's own server-side component is the only sanctioned
bridge from a browser to this API (or to nestjs's).** Reader's frontend
JS never calls `biblioteca.scriptorium.ro/api/**` directly - it calls its
*own* origin (`/tb/api/**` for this server, `/vz/api/**` for nestjs), and
reader's Node backend relays that to `BIBLIOTECA_SERVER_URL`/`NESTJS_URL`
(internal addresses) server-side. That relay is where "what's public"
actually gets decided - once, explicitly, in one place - rather than as a
URL-naming convention every future endpoint on this server would have to
remember to honor.

**A confirmed gap, not yet fixed**: as of this writing, reader's
`/tb/*`/`/vz/*` relay (`server.ts`) is an *unconditional wildcard*
forwarder - `app.get('/tb/*', ...)` relays any path after `/tb`, cookies
included, and `/vz/*` does the same to nestjs, which has no auth on it at
all. That means the relay itself currently defeats this whole policy:
anyone on the internet can already reach `/tb/api/admin/**`,
`/tb/actuator/**`, `/tb/api/internal/**`, or - the sharpest edge -
`POST /vz/api/vector-store/reset` and `/vz/api/vector-store/remove-opus`,
the explicit *manual-only* vector-drop endpoints, entirely
unauthenticated. **The fix belongs in biblioteca-reader, not here**: the
relay must become an explicit allowlist of the specific upstream paths
reader's own frontend actually calls (`auth/google`, `auth/logout`,
`users/me`, `reading-progress*`, `lucene/status`, `divs*`, `authors*`,
`collections/system/repos`, `drest/teiDivs/search/findOpera`, `search/*`
on the `/tb` side; `api/vectorizing*`, `api/status` on the `/vz` side),
rejecting everything else - matching the same "default-blocked, explicit
exception" posture as the Caddy rule below. Tracked, not yet applied.

**Enforcement, in full:**
1. Caddy on `biblioteca.scriptorium.ro`: no direct route to this
   server's or nestjs's `/api/*` or `/actuator/*` at all - only the
   reading-surface routes and reader's own `/app/*`, `/tb/*`, `/vz/*`
   handle blocks exist.
2. Inside reader: `/tb/*` and `/vz/*` become explicit allowlists (see
   above), not wildcard passthroughs - this is the actual public/private
   boundary, enforced once, in reader's own code.
3. App-level auth on this server (admin gating, per-user checks) stays
   exactly as-is underneath both of the above - it answers "which
   signed-in user", not "which network may call this at all".

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
  embedding-based similarity search via the active vector store (qdrant by
  default, milvus still supported — `vector.store`). `VectorSearchAvailability`
  checks the embedder and the store once at startup and
  disables vector search gracefully for the rest of that run if either is
  unreachable — `/api/search/milvus`/`/api/search/ann` return empty
  results instead of throwing.

#### Search data is precious: retention and reuse, manual-only drops

Embeddings and Lucene documents take **days** to recompute for the corpus —
they are assets, not disposable indexes. The policy, enforced end to end:

- **No automatic flow drops search content.** A reimported book is
  reindexed in place (`LuceneIndexService.reindexOpus` deletes only that
  opus's own url-prefix documents and re-adds them); a book removed from
  the repo keeps both its Lucene documents and its stored vectors
  (`AdminService.pruneRemovedTeis` prunes the DB rows and emits the
  `opusRemoved` event, but never touches the index; the vectorizer
  receives the event, logs it and *retains* its vectors). The
  manual-only escape hatches are `LuceneIndexService.removeOpus` /
  `POST /api/admin/lucene/reindex` (full deliberate wipe-and-rebuild), and
  the vectorizer's `POST /api/vector-store/remove-opus` / `reset`.
- **Stale hits are filtered, not deleted.** A removed book's urls 404;
  `UrlContentResolver` returns null on resolution failure and
  `VectorUtils` drops null-content hits — the user never sees a dead
  link, while the retained data stays recoverable until a manual
  operation removes it. (Lucene hits are served from the index's own
  stored content, so they keep working until the same manual cleanup.)
- **Vectors are reused by sha256.** On reimport the vectorizer embeds
  only paragraphs whose sha256 has no stored vector yet, and *repoints*
  unchanged paragraphs to renamed urls without touching their embeddings
  — see the vectorizer README's "Vectors are precious" section for the
  full mechanics.
- Covered by `LuceneReindexRetentionPolicyTest`,
  `AdminServiceRetentionPolicyTest` (server) and `vector_reuse.spec.ts`
  (vectorizer).

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

`.dao`, `.model`, `.dto` — Spring Data JPA over Apache Derby (Network
Server mode; `DB_URL` was `MYSQL_URL` before the engine switch, see the
environment variables table above), with a local Caffeine + on-disk
(`cache.dir`) cache layer (`.cache`). `.kafka` handles scheduled/async
work (`.scheduled`) — notably notifying `biblioteca-nestjs` (a separate
repo) of new/reimported opera so it can vectorize them.
`KafkaProps.java` has the real topic names (`biblioteca_*` prefix); this
service is the sole producer of every one of them.

Schema evolution is Hibernate `ddl-auto=update` for routine column/table
changes, with Flyway (`src/main/java/db/migration`, Java-based
migrations) reserved for the rare fix-forward migration `ddl-auto` can't
express on its own — see each migration class's own comment for why it
exists. `V2__Switch_identity_columns_to_pooled_sequences` moved every
entity's id generation from `IDENTITY` to a pooled `SEQUENCE`
(`allocationSize=50`) so Hibernate's JDBC insert batching actually
batches (`IDENTITY` categorically defeats batching regardless of
`jdbc.batch_size`); each sequence is seeded to start above its table's
current max id at migration time.

**Known gap, found 2026-09-29:** that "seed above current max id" logic
has a read-then-seed race on any table under concurrent writes —
`opus_vectorizing_stat` (biblioteca-nestjs posts to it continuously) hit
exactly this right after a prod deploy: `23505` duplicate-key spam for
~15 minutes, which only self-healed because Hibernate's pooled optimizer
burned through enough wasted id blocks to climb past the stale seed. A
follow-up audit of every sequence-backed table found two more silently
unsafe the same way, not yet hit by real traffic — `app_user_seq` and
`author_media_seq`, both seeded at exactly the existing max id (an
immediate collision on the very next insert into either table). All
three were fixed manually in prod: Derby has no
`ALTER SEQUENCE ... RESTART WITH`, so it's `DROP SEQUENCE` +
`CREATE SEQUENCE` with the same `INCREMENT BY`, reseeded with real
headroom (`max(id) + 10000`, not `+1`). The migration code itself is
still unfixed — any future fresh deploy under concurrent write load can
reproduce this (`sql/check_sequences.groovy` re-checks every
sequence-backed table on demand, see `sql/README.md`). Also worth
noting: the actual 0.9.10 release image runs a third migration,
`V3__Reseed_id_sequences_above_existing_rows`, that does **not** exist
in this checkout of `main` (no file, no commit) — it apparently only
ever landed on the `v0.9.10` release branch and was never merged back;
worth pulling forward before the next release silently loses it again.
`docs/prod-schema.sql` is a `dblook`-exported snapshot of prod's actual
schema taken right after this fix (reference only, not the source of
truth).

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
