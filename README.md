# Biblioteca Server

The text database behind [Biblioteca](https://biblioteca.scriptorium.ro): a
library of classical and philosophical texts encoded in TEI P5 XML, where
every author, work, chapter, paragraph - and any character range inside one -
has its own stable URL.

```
          TEI repositories (scriptorium-masters build, git repos)
                               │ import
                               ▼
 biblioteca-reader ──/tb/api──▶ biblioteca-server ──Kafka──▶ biblioteca-nestjs
   (public site)                (texts, search,        (vectors, enrichment,
                                 accounts)              chat, covers)
```

## What it does

- **Serves the corpus over plain, crawlable HTTP** -
  `GET /{author}/{work}/{...chapter}` as HTML, plain text, TEI XML or a JSON
  AST, by content negotiation or suffix (`.html`, `.txt`, `.xml`, `.json`).
- **Addresses any passage**, down to a character range inside a paragraph:
  `/strabo/the_geography_of_strabo/book_xiv/chapter_ii/20/_2:93..2:403`.
- **Searches** in three modes behind one response shape: literal scan,
  per-language full-text (Lucene) and semantic similarity (embeddings).
- **Keeps accounts** (Google One Tap or username/password) with reading
  progress, preferences and personal collections.
- **Exports** the corpus as a read-only, mountable DAV filesystem.
- **Publishes its OpenAPI spec** (`/api/docs.html`), from which the sibling
  services generate their clients.

## Quick start

Prerequisites: Java 25, a PostgreSQL database (`DB_URL`), and at least one
TEI repository (`TEI_REPOS`). Apache Derby, the engine before Dusky Discus,
is retired: a Derby `DB_URL` stops the server at startup. The vector store, Ollama and Kafka
enable semantic search and vectorization, but the server boots and serves
texts without them.

Every Biblioteca project has the same Rake interface. The tasks load the
`biblioteca/<profile>` pass-store profile, by default the machine's
hostname:

```bash
rake run                  # bootRun on http://localhost:8080
rake "run[zmeu]"          # with an explicit profile
rake unittest             # network-free unit tests
rake test                 # unit + integration tests
rake "test[,FragmentControllerITest]"   # only matching test classes
rake ci                   # clean, test, build, publish the Docker image (TeamCity)
```

Open `http://localhost:8080`, with the API docs at `/api/docs.html`.
Browsers are sent on to the reader app; add `?noredirect` for the server's
own Thymeleaf pages.

> On zmeu, tests must not run against the shared development database: see
> [AGENTS.md](AGENTS.md) for the database override. Prefer native Groovy test
> doubles over Mockito.

Other tasks: `rake tests` lists the test classes, `rake check-env` checks
the profile's variables, `rake sync-openapi` refreshes the checked-in
OpenAPI YAML, `sql/run.sh` runs ad-hoc Groovy JDBC scripts against a
profile's database, and
`rake minio-mount` / `rake minio-unmount` mount the profile's MinIO bucket
read-write at `~/s3-mount/biblioteca-<profile>` (needs `s3fs`).

**GraalVM native image.** `rake graalvm[profile]` compiles the server into
one executable (`rake ci-graalvm` builds and publishes it as
`<version>-graalvm` on TeamCity), and `rake graalvm-metadata` regenerates
what the image must be told, from the whole test suite run under GraalVM's
tracing agent. It starts in about a second with a third of the memory;
deployments switch to it per stack, after the JVM release - see
[native/README.md](native/README.md).

## Configuration

Spring profiles (`src/main/resources/application-<profile>.properties`)
reference environment variables. They come from the pass store through
`rake`, or from a gitignored `.env.<profile>` that Gradle reads, with the
real environment always winning. [`.env.example`](.env.example) lists every
variable.

| Variable | Purpose |
| --- | --- |
| `DB_URL` | PostgreSQL JDBC URL, credentials embedded |
| `TEI_REPOS` | Comma-separated TEI repositories (below) |
| `WORK_DIR` | Persistent work area: `cache/`, `git-repos/`, `lucene-index/`, `sessions/` |
| `VECTOR_STORE`, `VECTORSTORE_URL` | `qdrant` (default) or `milvus`, and its URL with port |
| `VECTOR_COLLECTION`, `VECTOR_COLLECTION_PREFIX` | Default `biblioteca_paragraphs_bge_m3`; the prefix (`dev-`, `int-`) lets non-production share the store |
| `EMBEDDER_URL` | Ollama URL with port |
| `KAFKA_BROKERS` | Kafka brokers |
| `BIBLIOTECA_EXTERNAL_URL` | The public base URL |
| `GOOGLE_OAUTH_CLIENT_ID` | Enables Google sign-in (unset disables it) |
| `ADMIN_USERS` | Emails of the administrators |

**TEI repositories.** Each entry is `url|basepath|filespec`:

```bash
/corpus/tei                                       # local folder, all TEI XML
https://github.com/petrul/universal-literature-tei/  # git, **/*.tei.xml
https://github.com/petrul/romcorpus|md|**/*.md       # git, a subfolder, Markdown
```

Git repositories are cloned read-only under `WORK_DIR/git-repos/` and pulled
(`--ff-only`) on startup. With the `autoimport` profile, new or changed
files are imported every minute, and books removed from a repository are
pruned hourly.
In Docker, local entries are host paths that the runner mounts into the
container.

## API at a glance

| Surface | What it is |
| --- | --- |
| `GET /{author}/{work}/{...path}` | A work or any part of it; `?depth=N` limits sub-chapters (`0` = the TOC-like shallow view) |
| `.../_N`, `.../_2:93..2:403` | A paragraph or an exact text range: a quotation card for browsers, the text for crawlers |
| `/api/fragments/*` | Fragments as JSON |
| `/api/authors/page`, `/api/works` | The paged catalogue (`page`, `size`, `q`, `lang`) |
| `/api/search/{grep,lucene,vector,ann}` | The three search modes, one `HitDto` shape ([details](docs/search.md)) |
| `/api/users/*`, `/api/auth/google` | Accounts and sign-in ([details](docs/authentication.md)) |
| `/api/collections/mine/*` | A user's own collections, `favorites` created at sign-up |
| `/api/collections/system/*` | Computed public collections: by language, author, source |
| `/dav` | The read-only DAV export ([details](docs/dav-export.md)) |
| `/api/admin/*` | Administration: imports, reindexing, runtime configuration |
| `/api/drest/*` | Spring Data REST; full CRUD only on authors, elements and divs, for biblioteca-nestjs |
| `/graphql` | Read queries over the entities; needs `BIBLIOTECA_INTERNAL_API_TOKEN` |

**Public vs internal.** The internet reaches only the reading surface (`/`,
`/app`, fragment URLs, TOC and search pages). `/api/**`, `/graphql` and
`/actuator/*` are internal: sibling services call `http://server:8080` on
the Docker network. The one bridge from a browser is biblioteca-reader's
server-side relay (`/tb/api/**`), an explicit allowlist of the paths its
frontend uses.

**Generated clients.** biblioteca-nestjs and biblioteca-reader both generate
typed clients from `/api/docs` with `rake gen-client`, against a running
server at the same commit. Whenever a REST contract changes, regenerate them
and commit the generated files with the change. The reader's allowlist build
fails loudly when a path it uses disappears.

**Runtime configuration.** `GET /api/admin/runtime-config` shows an
allowlisted view of the operational settings. `PUT`/`DELETE
/api/admin/runtime-config/{key}` change or reset one for the running JVM
only; settings marked `restartRequired` need a restart.
`GET /api/admin/config`, which biblioteca-nestjs reads, comes from the same
store.

## How it works

- **TEI processing** (`tei`, `xslt`, `toc`) - TEI is imported and rendered
  with Saxon XSLT (`src/main/resources/xslt/teidiv2html.xsl`). Each document's
  language is detected once at import (`lingua`).
- **Search** - [docs/search.md](docs/search.md). Embeddings and Lucene
  documents take days to rebuild, so **no automatic flow ever drops search
  data**.
- **Persistence, caches, Kafka** - [docs/persistence.md](docs/persistence.md).
- **Sessions** last 30 days and survive restarts
  ([docs/authentication.md](docs/authentication.md)).

| Package (`ro.editii.scriptorium.*`) | What's there |
| --- | --- |
| `rest`, `web` | REST controllers, fragment URLs, page decoration |
| `service` | Core logic: `AdminService` (imports, prune), `DivService`, language detection |
| `tei`, `xslt`, `toc` | TEI repositories and import, XSLT, tables of contents |
| `search`, `vector`, `embedding` | Grep, Lucene and vector search; embedders |
| `fragment` | Fragment address resolution and quotation cards |
| `collection` | Personal and system collections |
| `security` | Accounts, Spring Security, Google ID tokens |
| `model`, `dto`, `dao` | JPA entities, API DTOs, repositories |
| `graphql`, `dav` | The GraphQL and DAV surfaces |
| `cache`, `kafka`, `events`, `scheduled` | Caching, messaging, scheduled work |
| `media`, `client`, `health` | Images, outbound HTTP, health checks |

To find your way in, start at the controller in `rest`/`web` for an
endpoint and follow its service.

Further specifications: [fragment presentation](docs/fragment-presentation-spec.md),
[collections](docs/collections-spec.md), [UI](docs/ui-spec.md).

## Releases

Every release carries a name: an abstract adjective welded to an antiquity
noun, alliterative, walking the alphabet one release at a time. The first
named release is 0.9.12, *Amber Amphora*. When a release is cut, the next
one's name is fixed at random and stamped as the `releaseName` property
into every family manifest — `build.gradle` here, `package.json` in the
vectorizer, reader and covers — so every repo knows which named family
release its `-SNAPSHOT` is heading toward (the family cuts together, one
name per family quartet; `git-do-release.sh` bumps the versions). The next
release is *Fathomless Fountain*.

The current release is *Ethereal Exedra*, cut 2026-11-01:

| Project | Stable (*Ethereal Exedra*) | Release commit |
| --- | --- | --- |
| `biblioteca-server` | 0.9.16 | `e53c174` |
| `biblioteca-nestjs` | 0.9.15 | `93314b3` |
| `biblioteca-reader` | 0.1.17 | `7724efa` |
| `biblioteca-covers` | 0.0.6 | `9d145dd` |

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

### Bronze Basilica (2026-10-11)

| Project | Stable | Release commit |
| --- | --- | --- |
| `biblioteca-server` | 0.9.13 | `8bcc136` |
| `biblioteca-nestjs` | 0.9.12 | `4a89625` |
| `biblioteca-reader` | 0.1.14 | `b78d34f` |
| `biblioteca-covers` | 0.0.3 | `558fb78` |

- Repository-backed GraphQL queries for all JPA entities and REST catalog read equivalents
([c4534bc](https://github.com/petrul/biblioteca-server/commit/c4534bc)), with isolated native Groovy mocks and integration coverage
([bb1fa38](https://github.com/petrul/biblioteca-server/commit/bb1fa38)).
- Automatic missing-cover enrichment, deliberate cover replacement, and synchronous per-work cover endpoints
([f61da1d](https://github.com/petrul/biblioteca-server/commit/f61da1d),
[b832966](https://github.com/petrul/biblioteca-server/commit/b832966),
[225b1ae](https://github.com/petrul/biblioteca-nestjs/commit/225b1ae)).
- Chapter swipe navigation, card-turn animation and bookmark fixes
([851862f](http://mini.local:7080/gitlab/editii/biblioteca-reader/-/commit/851862f),
[215c703](http://mini.local:7080/gitlab/editii/biblioteca-reader/-/commit/215c703)); work-card blue is now `#003366`
([5f7e037](http://mini.local:7080/gitlab/editii/biblioteca-reader/-/commit/5f7e037)).
- Packaged reader startup is checked during builds, with its OpenAPI client bundled correctly
([74c287b](http://mini.local:7080/gitlab/editii/biblioteca-reader/-/commit/74c287b)).
- Cover theme configuration and saved cover request JSON
([ff00078](https://github.com/petrul/biblioteca-covers/commit/ff00078),
[321ca09](https://github.com/petrul/biblioteca-covers/commit/321ca09)).

When it was cut, the next release's name was fixed at random: *Creative Cella*.

### Creative Cella (2026-10-12)

| Project | Stable | Release commit |
| --- | --- | --- |
| `biblioteca-server` | 0.9.14 | `e546a6d` |
| `biblioteca-nestjs` | 0.9.13 | `54a2566` |
| `biblioteca-reader` | 0.1.15 | `b87e83c` |
| `biblioteca-covers` | 0.0.4 | `4ebdfb0` |

A fix release for Bronze Basilica:

- Post-import hooks (incremental Lucene reindex, author language backfill)
  run in a transaction, so PostgreSQL can read the imported opera's large
  objects - every import used to skip them with "Unable to access lob stream"
([95671e8](https://github.com/petrul/biblioteca-server/commit/95671e8)).
- Sign-out no longer fails when Google's script is missing or only partly
  loaded, which left the reader signed in
([e8517b1](http://mini.local:7080/gitlab/editii/biblioteca-reader/-/commit/e8517b1)).
- Tests keep their work files inside the build, also on CI agents
([5a9e06d](https://github.com/petrul/biblioteca-server/commit/5a9e06d)).

When it was cut, the next release's name was fixed at random: *Dusky Discus*.

### Dusky Discus (2026-10-15)

| Project | Stable (*Dusky Discus*) | Release commit |
| --- | --- | --- |
| `biblioteca-server` | 0.9.15 | `b482492` |
| `biblioteca-nestjs` | 0.9.14 | `ad81ab1` |
| `biblioteca-reader` | 0.1.16 | `e5f333a` |
| `biblioteca-covers` | 0.0.5 | `dfe0ce7` |

- Production now uses a fresh PostgreSQL database. At the user's explicit
  request, Derby user-data migration and data backups were skipped.
- Long Lucene queries retry with fewer words instead of returning a server error
  ([1688e9b](https://github.com/petrul/biblioteca-server/commit/1688e9b)).
- Canonical reader/text URLs and shared-selection semantic search
  ([92ffb68](https://github.com/petrul/biblioteca-reader/commit/92ffb68),
  [e035ed3](https://github.com/petrul/biblioteca-reader/commit/e035ed3)).
- Work art enrichment, verified cover storage, and the restored cover renderer
  ([0a599b2](https://github.com/petrul/biblioteca-nestjs/commit/0a599b2),
  [df061af](https://github.com/petrul/biblioteca-nestjs/commit/df061af),
  [672df9a](https://github.com/petrul/biblioteca-covers/commit/672df9a)).

The next family release name was randomly selected: *Ethereal Exedra*.

### Ethereal Exedra (2026-11-01)

| Project | Stable (*Ethereal Exedra*) | Release commit |
| --- | --- | --- |
| `biblioteca-server` | 0.9.16 | `e53c174` |
| `biblioteca-nestjs` | 0.9.15 | `93314b3` |
| `biblioteca-reader` | 0.1.17 | `7724efa` |
| `biblioteca-covers` | 0.0.6 | `9d145dd` |

- Chat with the library, a book or a collection: hybrid retrieval and RAG
  over the shared Ollama
  ([33e5118](https://github.com/petrul/biblioteca-nestjs/commit/33e5118)),
  for signed-in users only, one message at a time, 60 an hour and 400 a day
  ([2d2e98d](https://github.com/petrul/biblioteca-reader/commit/2d2e98d)).
- Every chat, question and RAG search is published on `biblioteca_events`
  with its user ([2ac1846](https://github.com/petrul/biblioteca-nestjs/commit/2ac1846),
  [862b44d](https://github.com/petrul/biblioteca-nestjs/commit/862b44d)).
- Every log line of every service names its initiator: the user, a
  generated guest name, anonymous or system
  ([1bc1fde](https://github.com/petrul/biblioteca-server/commit/1bc1fde),
  [08e1025](https://github.com/petrul/biblioteca-server/commit/08e1025)).
- Unified fragment addresses and quotation cards; a shared quote previews as
  its card, a 1200x630 PNG og:image
  ([958f12a](https://github.com/petrul/biblioteca-server/commit/958f12a),
  [433af14](https://github.com/petrul/biblioteca-reader/commit/433af14),
  [783be74](https://github.com/petrul/biblioteca-reader/commit/783be74)).
- Home page shelves, a Discover collection for guests and a catalogue page
  ([9bff77f](https://github.com/petrul/biblioteca-reader/commit/9bff77f),
  [961063d](https://github.com/petrul/biblioteca-server/commit/961063d)).
- Drama: speaker cues and stage directions
  ([2e013d8](https://github.com/petrul/biblioteca-reader/commit/2e013d8)).
- Reading limits that tell people from scrapers; per-request limits in one
  place ([d714884](https://github.com/petrul/biblioteca-reader/commit/d714884),
  [97f3e5d](https://github.com/petrul/biblioteca-server/commit/97f3e5d)).
- Security: GraphQL and the vectorizer APIs only for callers holding
  BIBLIOTECA_INTERNAL_API_TOKEN; covers proxies only allowlisted images
  ([fd427ff](https://github.com/petrul/biblioteca-server/commit/fd427ff),
  [1c3bd9e](https://github.com/petrul/biblioteca-nestjs/commit/1c3bd9e),
  [5947ccc](https://github.com/petrul/biblioteca-covers/commit/5947ccc)).
- Reimporting a TEI file serves its new text at once
  ([2f10ec0](https://github.com/petrul/biblioteca-server/commit/2f10ec0)).

A fix release followed on 2026-11-07, `biblioteca-nestjs` 0.9.16
(`a44eb3d`): v0.9.15 plus only the vectorizer's Kafka fix - it commits after
every message and fetches small batches, so a group eviction no longer
replays a whole ~25-minute batch (production had been re-vectorizing the
same ~485 opera all day). Cut by hand from v0.9.15, not from main: main's
nestjs already required the internal token on the chat routes, which
production's reader 0.1.17 does not send.

The next family release name was randomly selected: *Fathomless Fountain*.

### Fathomless Fountain (next — in development)

| Project | Snapshot |
| --- | --- |
| `biblioteca-server` | 0.9.17-SNAPSHOT |
| `biblioteca-nestjs` | 0.9.17-SNAPSHOT |
| `biblioteca-reader` | 0.1.18-SNAPSHOT |
| `biblioteca-covers` | 0.0.7-SNAPSHOT |

So far:

- The GraalVM native image starts and serves (0.9.16-graalvm restarted in a
  loop): Flyway's migrations listed instead of scanned, ICU4J, Caffeine,
  Groovy and Lucene's native calls declared from the test suite run under
  the tracing agent, persisted sign-in sessions restorable
  ([native/README.md](native/README.md)).
- Cover art from any public image host for administrators, with the reason
  when it cannot be loaded, and uploaded images
  ([biblioteca-covers](https://github.com/petrul/biblioteca-covers),
  biblioteca-reader, biblioteca-nestjs).

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

## License

Copyright 2026 Editii Scriptorium srl. Licensed under the [Apache License, Version 2.0](LICENSE); see also [NOTICE](NOTICE).
