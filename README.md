# Biblioteca Server

A structured digital library serving classical and philosophical texts —
encoded in TEI P5 XML — as an addressable text database. Every author, work,
chapter, paragraph, and even an arbitrary character range has its own stable
URL, down to a single quotable sentence.

## What it does

- Serves the corpus over plain, crawlable HTTP: `GET /{author}/{work}/{...chapter-path}`
  in HTML (site-chrome or bare), plain text, TEI XML, or a JSON AST — content-negotiated
  or suffix-selected (`.txt`, `.xml`, `.json`, `.html`).
- Cites/embeds permanent links to *any* text selection, down to a character
  range inside one paragraph (see [Fragment quotes](#fragment-quotes-get-quote)).
- Three search modes behind one response shape — live literal scan, per-language
  full-text index, embedding-based similarity (see [Search](#search)).
- Exposes the whole corpus as a read-only, mountable DAV filesystem
  (see [DAV export](#read-only-dav-export)).
- Signed-in readers keep personal collections (Google Sign-In or username/password).
- Publishes its OpenAPI spec (`/api/docs.html`, `/api/docs`) — the source of the
  generated API clients in both sibling repos.

## Quickstart

Prerequisites: Java 25, a reachable Apache Derby Network Server (`DB_URL`, image
built by `docker/derby`), and at least one configured TEI repository. A vector
store, Ollama, and Kafka are needed for the full feature set (vector search,
async vectorization) but not to boot and browse — vector search degrades
gracefully without them.

Repositories are configured in the required `TEI_REPOS` list, comma-separated
entries of the form `url|basepath|filespec`:

```bash
# Local repository; default recursive TEI XML selection.
/corpus/tei

# Git repository at its root; default recursive **/*.tei.xml selection.
https://github.com/petrul/universal-literature-tei/

# Git repository, restricted to a subdirectory and Markdown sources.
https://github.com/petrul/romcorpus|md|**/*.md
```

Plain paths and `file:` URLs are local repositories; SSH/HTTP(S)/Git URLs are
cloned read-only with the system `git` (non-interactive). Checkouts persist under
`WORK_DIR/git-repos/<sha256-of-url>`, updated with `git pull --ff-only` on startup.
Local and Git-backed sources are treated uniformly after checkout. For Docker
deployments, `TEI_REPOS` uses host paths for local entries; the deployment
wrapper mounts and translates them, Git entries stay unchanged.

`WORK_DIR` is the persistent work area: `cache/` for application caches,
`git-repos/` for checkouts, `lucene-index/` for the search index.

```bash
git clone <this repo>
cd biblioteca-server
cp .env.example .env.dev   # fill in Derby/TEI-repo/etc values
./gradlew bootRun -x test  # -Pdev is the default profile
```

Open `http://localhost:8080` — server-rendered and crawlable; API docs at
`/api/docs.html`. Browsers are sent to the reader app automatically; `?noredirect`
serves the legacy Spring MVC/Thymeleaf UI instead (navigation links preserve the
parameter).

Config is layered as Spring profiles (`application-<profile>.properties` in
`src/main/resources/`, selected via `-Pdev`/`-Pci`/`-Pprod`/`-Pair`/`-Pint` or
`SPRING_PROFILES_ACTIVE`), with a gitignored `.env.<profile>` supplying the
referenced variables. Gradle loads it — `build.gradle`'s `ProcessForkOptions`
hook reads `.env.${profile}` before any `bootRun`/`test` and fills in anything
not already in the real environment (CI/Vault values always win) — so this
works via `gradlew` directly or through `rake` (a thin wrapper, no env logic of
its own). `.env.example` documents every variable.

## Environment variables

| Variable | Purpose |
| --- | --- |
| `DB_URL` | JDBC URL of the Derby Network Server, credentials embedded as Derby connection attributes (`;user=...;password=...`); engine-agnostic name (previously `MYSQL_URL`) |
| `TEI_REPOS` | Comma-separated local or Git-backed TEI repository specifications |
| `WORK_DIR` | Persistent application work directory (`cache/`, `git-repos/`, `lucene-index/`) |
| `VECTORSTORE_URL` | URL of the active vector store, including port (`MILVUS_URL` still accepted as the legacy name) |
| `VECTOR_STORE` | Vector store backend: `qdrant` (the default) or `milvus` |
| `VECTOR_COLLECTION` | Base collection name; defaults to `biblioteca_paragraphs_bge_m3` |
| `VECTOR_COLLECTION_PREFIX` | Prefix so non-prod environments share the prod store (dev/int profiles set `dev-`/`int-`); empty by default |
| `EMBEDDER_URL` | Ollama URL, including port |
| `KAFKA_BROKERS` | Kafka broker address(es) |
| `BIBLIOTECA_EXTERNAL_URL` | Public/base URL advertised by the application |
| `GOOGLE_OAUTH_CLIENT_ID` | Optional Google OAuth client ID (unset disables Google sign-in) |

## Running tests

```bash
./gradlew unittest          # network-free: no vector store/embedder needed (ci profile by default)
./gradlew test              # unit + integration-deps tests (ci profile by default)
./gradlew integrationTest   # only the integration-test tagged tests
```

Rake equivalents (loading the pass-store profile env): `rake unittest[dev]`,
`rake itest[dev]`, `rake test[dev]`; `rake ci` runs the full pipeline
(clean, test, build, Docker publish).

Mocking in Groovy tests: prefer map-coercion fakes (`[exists: { false }] as
VectorCollection`), hand-rolled Groovy fakes for class-typed collaborators, and
real cheap instances (temp-dir `LuceneIndexService`, plain `JdbcTemplate`)
over Mockito, which is fragile from Groovy (`UnfinishedStubbingException` etc.).
Where Mockito is genuinely needed, resolve each mock to a variable first and
never build one inside another `when()/thenReturn()` chain.

## Running bare-metal (dev)

Every service of the trio has `rake run`, which starts it bare-metal with its
pass-store profile environment (`biblioteca/<profile>`, profile from
`rake run[profile]`, `PROFILE=<profile>`, or the machine's short hostname):

```bash
SPRING_PROFILES_ACTIVE=autoimport,dev rake run   # server: gradle bootRun on :8080
rake run                                         # nestjs: Kafka consumer + vectorizer on :3000
rake run                                         # reader: tsx server.ts + Vite on :3333
```

Services talk over the host network: server `:8080` (`BIBLIOTECA_EXTERNAL_URL`),
nestjs `:3000`, reader `:3333` (what Caddy's test vhost proxies). The database
is the shared Derby Network Server; `WORK_DIR` is per-profile, so the Lucene
index resumes across restarts.

### Mounting the profile's MinIO bucket

```bash
rake minio-mount          # machine profile, or PROFILE if set
rake minio-mount[zmeu]    # explicitly loads biblioteca/zmeu
rake minio-unmount[zmeu]
```

The bucket is mounted **read-write** at `~/s3-mount/biblioteca-<profile>`.
After mounting, the task opens the directory with `open` or `xdg-open`.
`MINIO_URL` supplies the S3 endpoint and bucket (for example
`http://host:9000/biblioteca`); `MINIO_CREDS` supplies `access-key:secret-key`.
Both come from the selected pass-store profile using the existing environment
loader. No credentials are written to disk or passed as command arguments.
Install the local mount tools with `sudo apt install s3fs` on Ubuntu.

Unmounting uses the same profile default but does not load secrets or contact
MinIO. Close files and terminals using the mount before unmounting. Repeating a
mount/unmount is harmless; an existing read-only mount must be unmounted before
the read-write task can use that path. These mounts are manual, not persistent
across reboots.

## Docker

```bash
./gradlew docker            # build editii/biblioteca-server:<version> locally
./gradlew docker-publish    # also push to the mini.local:5000 registry
rake derby-ci               # the Derby image (docker/derby), built + published
```

### Apache Derby Network Server image

`docker/derby/` is the buildable source of truth for the standalone Derby
Network Server this app expects (no official Derby image exists on Docker Hub):

```bash
docker build -f docker/derby/Dockerfile -t biblioteca-derby docker/derby
docker run -d -p 1527:1527 -v <data-dir>:/var/lib/derby \
    -e DB_USER=... -e DB_PASSWORD=... biblioteca-derby
```

Credentials are required (the entrypoint refuses to start without them and
configures Derby's BUILTIN authentication), and `DB_URL` then points at
`jdbc:derby://<host>:1527/<db>;create=true;user=<user>;password=<password>`.
The image tag follows the Derby version, kept in sync with the `derbyclient`
dependency in `build.gradle` — never pinned independently.

---

## Architecture

Spring Boot 4.1 / Gradle, Java 25, package root `ro.editii.scriptorium`.

### REST / web layer

`.rest`, `.web` — the addressable fragment URLs, the basic-auth-protected
Admin API (`/api/admin/*`), and a Relocation table exposed via
spring-data-rest at `/api/drest/`. Documented with springdoc-openapi
(`/api/docs.html`, JSON `/api/docs`, YAML `/api/docs.yaml`, also checked in as
`textbase-swagger-api.json`).

That `/api/docs` document is the source of two downstream generated clients —
a REST-surface change ripples into both, and each regenerates off a *running*
server at the same commit the API changed:

- **biblioteca-nestjs**: `rake gen-client` → `src/biblioteca.api.ts`
- **biblioteca-reader**: `rake gen-client` → `src/generated/biblioteca-server-api.d.ts`
  plus the public allowlist (`scripts/build-openapi-public.mjs` →
  `openapi-public.json`) the browser client is typed against

Both repos check their generated clients in, so the diff is the reviewable
record of the API change. A renamed/removed endpoint makes the reader's
allowlist build fail loudly — update `BIBLIOTECA_PATHS` there when deliberately
changing a consumed path.

`GET /{author}/{opus}/{...path}` fetches one fragment, from the whole work down
to a single sub-chapter, defaulting to the full text including all sub-chapters.
An optional `?depth=N` caps that (`depth=0` = only the div's own direct content,
the TOC-like shallow view). Depth counts addressable path segments, not raw TEI
`<div>` nesting — structural wrapper divs without their own `<head>` stay
transparent.

The reader catalogue is page-oriented: `GET /api/authors/page` and
`GET /api/works` take one-based `page`/`size` and return `items`, `totalItems`,
`totalPages`; both accept `q`, works also `lang`. Consumers should use these
instead of downloading the complete collections into browser memory.

Most `/api/drest/**` repositories exclude writes (`@RestResource(exported =
false)`) or exclude themselves entirely (child/association tables, or entities
like `AppUser`/`ReadingProgress` needing real business logic). `AuthorRepository`,
`TeiElemRepository`, and `TeiDivRepository` are the exception: full CRUD, usable
by biblioteca-nestjs directly.

### Public vs internal REST endpoints

**The entire REST API (`/api/**`) and `/actuator/*` are never reachable from
the internet.** Caddy on `biblioteca.scriptorium.ro` exposes only the reading
surface (`/`, `/app`, fragment URLs, TOC/search pages, `/quote`) and reader's
`/tb/*`/`/vz/*` blocks; every sibling service reaches this API over the
trusted docker network (`http://server:8080`, never the public hostname).

**biblioteca-reader's server-side relay is the only sanctioned bridge from a
browser to this API.** The reader's frontend calls its own origin
(`/tb/api/**` here, `/vz/api/**` for nestjs); reader's Node backend
(`server.ts`) forwards to the internal upstreams. The relay is an explicit
**allowlist** of exactly the paths the frontend uses (`auth/google`,
`auth/logout`, `users/me`, `reading-progress*`, `lucene/status`, `divs*`,
`authors*`, `collections/system/*`, `drest` search, `search/*` on the `/tb`
side; `vectorizing*`, `status` on the `/vz` side) — cookies included, default
blocked, everything else rejected. The public/private boundary is decided
once, there, rather than by URL naming conventions. App-level auth (admin
gating, per-user checks) stays as-is underneath.

### Runtime configuration (writable actuator)

Admin-only `GET /api/admin/runtime-config` exposes an allow-listed view of
operational settings initialized from the profile's environment (`DB_URL`,
`EMBEDDER_URL`, `KAFKA_BROKERS`, `TEI_REPOS`, ...). `PUT
/api/admin/runtime-config/{key}` changes a value in memory for the current
JVM; `DELETE` resets it to the startup value. Nothing is written back to the
pass store, `.env` files, or the database. Database URLs are masked unless an
authenticated administrator passes `?reveal=true`. Settings used to construct
a connection pool or client are reported with `restartRequired: true` —
changing them updates the runtime property source but does not rebuild an
already-running client. `GET /api/admin/config` (consumed by biblioteca-nestjs)
reads from the same runtime store.

### TEI processing

`.tei`, `.xslt`, `.toc` — parses/imports TEI XML (originals authored as flat-ODT,
piped odt → TEI → web) using Saxon for XSLT; builds tables of contents and
per-fragment addressing. `importTeiDivs` (Gradle task) / `TeiDivImporterCli`
handle bulk import outside the web server. A document's language is detected
once at import time from its own text (`LanguageDetectionService`, the `lingua`
library — no native/network dependency) and stored on `TeiFile.language` and
every `TeiDiv`/`TeiElem` row, with the directory path as a fallback only when
detection is inconclusive.

### Search

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

#### Search data is precious: retention and reuse, manual-only drops

Embeddings and Lucene documents take **days** to recompute — assets, not
disposable indexes:

- **No automatic flow drops search content.** A reimported book is reindexed in
  place; a book removed from the repo keeps both its Lucene documents and its
  vectors (`AdminService.pruneRemovedTeis` prunes DB rows and emits
  `opusRemoved`; the vectorizer logs it and *retains* the vectors). The
  manual-only escape hatches are `POST /api/admin/lucene/reindex` and the
  vectorizer's `POST /api/vector-store/remove-opus` / `reset`.
- **Stale hits are filtered, not deleted** — a removed book's urls 404 and the
  resolver drops null-content hits, so users never see dead links while the
  data stays recoverable.
- **Vectors are reused by sha256**: on reimport the vectorizer embeds only
  paragraphs without a stored vector and repoints unchanged paragraphs to
  renamed urls without touching embeddings.

Covered by `LuceneReindexRetentionPolicyTest`, `AdminServiceRetentionPolicyTest`
(server) and `vector_reuse.spec.ts` (vectorizer).

### Fragment quotes (`GET /quote`)

`.fragment` — a Fragment is an arbitrary selection within a `TeiDiv`'s subtree,
identified by the div's path plus start/end dot-paths (1-indexed, e.g.
`2.1.15` = child 2, child 1, character 15). `FragmentResolutionService`
resolves both points and spans one or several paragraphs, trimming the
first/last to their offsets.

```
GET /quote/{divPath}?start={dotPath}&end={dotPath}
```

```bash
curl 'https://biblioteca.scriptorium.ro/quote/bacon/of_gardens?start=7.4.0&end=7.4.85'
```

Renders as a standalone "quote card" — no site chrome — that drops cleanly into
an `<iframe>`. There is no UI yet for picking a quote by selecting text on the
page. `FragmentResolutionServiceTest` and `FragmentControllerITest` cover the
mechanics.

### Collections

`/api/collections/*` — named groupings of TeiDivs and/or Fragments:

- `/api/collections/mine/*` — real, persisted, per-`AppUser` collections
  (owner-authenticated, full CRUD). Every user gets an auto-created,
  non-deletable `favorites` collection at registration.
- `/api/collections/system/*` — public, read-only, computed on the fly:
  by-language, by-author, by-source-repo groupings.

### Accounts & sign-in

`.security` — real, persisted accounts, needed to own collections:

- **Username/password**: `POST /api/users/register`, then Spring Security's
  default session-based `formLogin` (`POST /login`).
- **Google Sign-In / One Tap** (primary path): the widget POSTs a Google ID
  token to `POST /api/auth/google`, verified via Google's `tokeninfo` endpoint
  (no JWT/JOSE library), find-or-creating an `AppUser` by the token's `sub`
  claim. Disabled until `GOOGLE_OAUTH_CLIENT_ID` is set — deliberately no
  guessed default. A Google-only account has no local password.

### Read-only DAV export

`.dav`, `/dav` — projects the corpus as a mountable
`language/author/work/chapter` virtual filesystem (`OPTIONS`, `PROPFIND`,
`GET`, `HEAD`; mutation methods return `405`).

| Parameter | Values | Default | Meaning |
| --- | --- | --- | --- |
| `format` | `txt`, `json`, `xml`, `xhtml` | `txt` | Serialization; `xml` is the original TEI fragment |
| `fragmentation` | `1`, `1.1`, `1.1.1` | `1` | Deepest division exposed as a file (work/chapter/subchapter) |
| `lang` | two-letter code | all | Restrict to works detected in that language |
| `author` | canonical author id | all | Restrict to that author's works |

Don't use the query-param form as a DAV *mount* URL (clients drop query
strings on child `href`s) — mount the path-configured form, which embeds the
same filters in the path:

```
/dav/_export/{format}/{fragmentation}/{language-or-all}/{author-or-all}/
```

`PROPFIND` supports depths `0` and `1` only; `Depth: infinity` is rejected so
one request can't materialize the whole corpus. Fragmentation selects a
frontier, not a requirement — each terminal file contains its complete
remaining subtree, so shallower branches never lose text.

### Persistence, caching, messaging

Spring Data JPA over Apache Derby (Network Server mode), with a Caffeine +
on-disk cache layer (`.cache`). `.kafka`/`.scheduled` handle async work —
notably notifying `biblioteca-nestjs` of new/reimported opera so it can
vectorize them; `KafkaProps.java` has the real topic names (`biblioteca_*`),
this service being the sole producer. `src/main/resources/static/asyncapi.yml`
(checked in, served with the app) documents each topic's message schema and
its actual producers/consumers.

Schema evolution is Hibernate `ddl-auto=update` for routine changes, with
Flyway (`src/main/java/db/migration`, Java-based) for fix-forward migrations
V1–V7 — including `V2` (IDENTITY → pooled SEQUENCE id generation so JDBC
insert batching actually batches) and `V3` (reseeding every sequence still
inside its table's used id range, the failure mode V2's create-only seeding
cannot repair — observed as `23505` duplicate-key spam in prod until fixed).
Derby has no `ALTER SEQUENCE ... RESTART WITH`, hence the drop/recreate
approach; `sql/check_sequences.groovy` re-checks every sequence-backed table
on demand (see `sql/README.md`). `docs/prod-schema.sql` is a `dblook` snapshot
of prod's schema taken after the fix (reference only).

### Frontend

The public reader app and the admin UI are a separate, standalone repo
(`biblioteca-reader`), built and deployed independently — this repo no longer
builds or serves an SPA. Server-rendered Thymeleaf templates (`teidiv.html`,
etc.) still serve the crawlable book pages directly from here.

---

## Package map

Every package lives under `ro.editii.scriptorium.*`:

| Package | What's there |
| --- | --- |
| `rest`, `web` | REST controllers, the DivController fragment endpoint, page decoration |
| `service` | Core business logic: `AdminService`, `DivService`, `LanguageDetectionService` |
| `tei`, `xslt`, `toc` | TEI repo/import plumbing, Saxon-based XSLT tooling, TOC building |
| `search`, `search.lucene`, `search.grep`, `vector` | The three search backends and the embedder abstraction |
| `fragment` | Dot-path fragment/quote resolution |
| `collection` | Personal and system collections |
| `dav` | The read-only DAV export surface |
| `security`, `security.google` | Auth (`AppUser`, Spring Security wiring, Google ID token verification) |
| `model`, `dto`, `dao` | JPA entities, API-facing DTOs, Spring Data repositories |
| `cache` | Caffeine + on-disk cache layer |
| `kafka`, `scheduled` | Async/scheduled work, including notifying downstream consumers |
| `client` | `TextbaseClient` — this server's own outbound HTTP client |
| `media` | Author/div media (image) associations |

Fastest orientation path: find the REST controller in `rest`/`web` for the
endpoint you care about, then follow its injected service into the matching
package above.

## Background

Originally named `scriptorium-repo`. The design goal from the start was a text
database addressable down to the paragraph, word, and letter — not another
ebook store. Source content is authored as flat-ODT and piped through an
odt → TEI → web pipeline at import time.

---

## Releases

Every release carries a name: an abstract adjective welded to an antiquity
noun, alliterative, walking the alphabet one release at a time. The first
named release is 0.9.12, *Amber Amphora*. When a release is cut, the next
one's name is fixed at random and stamped as the `releaseName` property
into every family manifest — `build.gradle` here, `package.json` in the
vectorizer, reader and covers — so every repo knows which named family
release its `-SNAPSHOT` is heading toward (the family cuts together, one
name per family quartet; `git-do-release.sh` bumps the versions). The next
release is *Bronze Basilica*.

The current release is *Amber Amphora*, cut 2026-10-07 — one row per
`biblioteca-*` project: the stable it shipped in this release, and the
short SHA of that stable's `Release <version>` commit (each repo's
`v<version>` branch tip — `git show <sha>` gives back the exact state
that shipped):

| Project | Stable (*Amber Amphora*) | Release commit |
| --- | --- | --- |
| `biblioteca-server` (this repo) | 0.9.12 | `789a349` |
| `biblioteca-nestjs` (vectorizer) | 0.9.11 | `8b0296d` |
| `biblioteca-reader` | 0.1.13 | `1679515` |
| `biblioteca-covers` | 0.0.2 | `ac95566` |

### Keeping this section current

For whoever (human or agent) cuts the next family release —
`git-do-release.sh` prints everything needed:

1. Rebuild the table above for the just-cut release — one row per
   `biblioteca-*` project: its stable in the new release and the short
   SHA of its `Release <version>` commit (the `v<version>` branch tip).
   Move the previous release's rows
   into its subsection below (create it, headed by the name and the
   release date), with a bullet list of its major improvements, each
   linked to its commit.
2. Fix the *next* release's name at random — next letter of the alphabet,
   abstract adjective + antiquity noun — stamp it as `releaseName` in all
   four family manifests (`build.gradle` here, `package.json` in the
   siblings), and start its subsection with a table of the snapshot
   versions the repos are heading toward it on.

Names began with 0.9.12 (*Amber Amphora*); earlier releases predate the
scheme and are archived in the last subsection.

### Amber Amphora (2026-10-07)

The first named release, cut 2026-10-07:

| Project | Stable | Release commit |
| --- | --- | --- |
| `biblioteca-server` | 0.9.12 | `789a349` |
| `biblioteca-nestjs` (vectorizer) | 0.9.11 | `8b0296d` |
| `biblioteca-reader` | 0.1.13 | `1679515` |
| `biblioteca-covers` | 0.0.2 | `ac95566` |

- Per-reader UI preferences — `GET/PUT /api/users/me/preferences`
([7bf927d](https://github.com/petrul/biblioteca-server/commit/7bf927d))
- Google auth: profile refresh endpoint and legacy account linking
([f927ff6](https://github.com/petrul/biblioteca-server/commit/f927ff6))
- TEI language exposed in div metadata
([8d3b196](https://github.com/petrul/biblioteca-server/commit/8d3b196))
- Author image URLs on `GET /api/authors/{strId}`
([03cb65b](https://github.com/petrul/biblioteca-server/commit/03cb65b))
- Qdrant collection parsed from `VECTORSTORE_URL`
([010a35a](https://github.com/petrul/biblioteca-server/commit/010a35a))

When it was cut, the next release's name was fixed at random:
*Bronze Basilica*.

### Bronze Basilica (next — in development)

The family release every `biblioteca-*` snapshot is heading toward; its
name is stamped as `releaseName` in all four manifests. Each repo's main
is working toward it on its own next version:

| Project | Snapshot |
| --- | --- |
| `biblioteca-server` (this repo) | 0.9.13-SNAPSHOT |
| `biblioteca-nestjs` (vectorizer) | 0.9.12-SNAPSHOT |
| `biblioteca-reader` | 0.1.14-SNAPSHOT |
| `biblioteca-covers` | 0.0.3-SNAPSHOT |

When the family cuts this release, the versions above become its stables
(add each `Release <version>` commit), the rows move up into the main
table, and the next release's name is fixed at random.

### Before the naming began (0.9.1 – 0.9.11)

The pre-name releases, kept for the record — the server version and the
sibling stables that shipped in each batch, each with its release commit:

| Server | Date | Vectorizer | Reader | Covers | Notes |
| --- | --- | --- | --- | --- | --- |
| 0.9.11 `6d706f1` | 2026-10-03 | 0.9.10 `b710f45` | 0.1.12 `5ad4901` | 0.0.1 `7a2f95a` | First release with `biblioteca-covers` in the family; Lucene 10; interactive Derby `ij` shell (`rake ij`); vector-search failures surface as errors, not empty results. |
| 0.9.10 `330f92b` | 2026-09-29 | 0.9.9 `cf70dc3` | 0.1.10 `9f706f5` | — | Schema V3 (reseeded id sequences); `domPath`-positional TEI node resolution; Saxon pinned for XPath/DOM serialization; fresher-sweep batched and slowed to 60s. |
| 0.9.9 `78c73be` | 2026-09-28 | 0.9.9 `cf70dc3` | 0.1.10 `9f706f5` | — | MySQL → Apache Derby migration (Network Server, in-repo `docker/derby` image, Flyway, `DB_URL` creds as connection attributes); TeiDiv referenced by stable path; hourly TEI prune. |
| 0.9.8 `9548da7` | 2026-09-27 | 0.9.8 `cb566b7` | 0.1.8 `d2a790f` | — | Search data is precious: retain on removal, manual-only drops; anti-bulk-download `robots.txt`; enrichment fetching moved to the vectorizer; public/internal API split settled. |
| 0.9.7 `889fce6` | 2026-09-27 | 0.9.8 `cb566b7` | 0.1.8 `d2a790f` | — | Pluggable vector store — Qdrant (default) alongside Milvus; seamless degradation while the vector store is absent; Actuator + Lucene rebuild progress endpoint. |
| 0.9.6 `72f812a` | 2026-09-25 | 0.9.7 `7cb860e` | 0.1.7 `5f05afb` | — | GraalVM native image boots the JPA layer (hibernate-graalvm + classpath resources); Milvus collection renamed `biblioteca_paras_bge_m3` with the nlist-8192 index pairing; public `/api/info` build identity. |
| 0.9.5 `097d7ca` | 2026-09-24 | 0.9.4 `9197cb3` | 0.1.5 `ac2e028` | — | `TextbaseServer` → `BibliotecaServer`; orphan sweep for vanished TEI sources; `ci-graalvm` pipeline. |
| 0.9.4 `f5e54d2` | 2026-09-22 | 0.9.2 `aa318fe` | 0.1.3 `f1c7755` | — | The `textbase` → `biblioteca` rename fallout: `BIBLIOTECA_EXTERNAL_URL`, pass-store keys, int-server asyncapi wiring. |
| 0.9.3 `0f3f814` | 2026-09-20 | 0.9.1 `ed15362` | 0.1.2 `908a1a2` | — | `git` in the runtime image + document-conversion toolbox base; non-blocking initial repo clone; Google One Tap FedCM. |
| 0.9.2 `c5d01b3` | 2026-09-19 | 0.9.1 `ed15362` | 0.1.2 `908a1a2` | — | Git-backed TEI repositories; enrichment switched from Ollama-generated to search-based. |
| 0.9.1 `63cac91` | 2026-09-15 | — | — | — | First stable of the 0.9 line: Google sign-in, auto-built Lucene index, Kafka login events, Ollama/SearXNG author bios and opus summaries. |
