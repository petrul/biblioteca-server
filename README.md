Run:

$ ./gradlew bootrun -x test

## Tests

The `unittest` task is network-free: external-service tests are excluded, and
application-context tests that do not exercise vector search use test doubles
for Milvus and the embedder.

```sh
./gradlew -Pci unittest
```

Tests whose purpose is to exercise the real integration-deps services are
tagged `external`. The conventional `test` task runs both the network-free and
external tests. Under the CI profile it uses Milvus at `zmeu.local:20112`,
sentence-transformers at `mini.local:11200`, and Ollama at
`zmeu.local:11434`.

```sh
./gradlew test
```

`./gradlew -Pci integrationTest` remains available when only the tagged
external tests are wanted. The complete `test` task defaults to the CI profile;
`rake test` delegates to it directly, and `rake ci` invokes that same Rake test
stage before building.

CI configuration may be injected directly as environment variables (for
example, TeamCity remote parameters backed by Vault). Process environment
values take precedence over local `.env.<profile>` files, which are retained
only as an optional developer fallback. Vault helper scripts and operational
documentation live in `/home/petru/work/scripts/docker/vault/`.


# TEXTBASE

It's a place where you can retrieve bits of texts of the whole humanity.
In very structured format: TEI.
So you can access every piece of it using sensible urls.
You can compose great PDFs for each book. 

# Architecture

A Spring Boot (4.1) / Gradle application, Java 25, `group = 'ro.editii'`.

- **REST/web layer** (`ro.editii.scriptorium.rest`, `.web`) — serves the addressable book/chapter/paragraph URLs, the Admin API (`/api/admin/*`, basic-auth protected), and the Relocation table (HTTP redirects for moved URLs) via `spring-data-rest` at `/api/drest/`. API is documented with springdoc-openapi at `/api/docs.html`; the generated definition is available as JSON at `/api/docs` and YAML at `/api/docs.yaml` (a JSON snapshot is also checked into the repo as `textbase-swagger-api.json`). The integration suite parses the live YAML, checks representative paths and responses, and resolves all internal references.
  - `GET /{author}/{opus}/{...path}` (optionally suffixed `.txt`/`.xml`/`.json`/`.html`, or content-negotiated via `Accept`; no suffix returns the decorated, site-chrome HTML reader page — `DivController`) fetches one fragment (any div, from the whole work down to a single sub-chapter). It defaults to the fragment's **full text, sub-chapters included, no matter how deep** — a request for a whole work returns the whole work in every format. An optional `?depth=N` query param caps how many levels of sub-chapters come along: `depth=0` returns only that div's own direct content (no sub-chapters at all — the shallow, TOC-like view this endpoint used to always return); `depth=N` (N > 0) keeps sub-chapters up to N levels below the requested div and prunes anything nested deeper; omitted (the default) keeps everything. Depth is counted in the same units as the div's own addressable sub-chapters/URL path segments, not raw TEI `<div>` nesting — a structural wrapper div with no `<head>` of its own (common in odt→TEI conversions) never got its own path segment at import time (`TeifileParser`) and is transparent here too, so it never silently costs you a depth level.
- **TEI processing** (`.tei`, `.xslt`, `.toc`) — parses/imports TEI XML sources (originals authored as flat-ODT, piped odt → tei → web) using Saxon for XSLT/XML rather than Xerces; builds tables of contents and per-fragment (down to paragraph/word) addressing.
- **Search** — three complementary search modes over the corpus, each its own `/api/search/*` endpoint, returning the same `HitDto` shape (`type` distinguishes them):
  - **Grep** (`.search.grep.GrepSearchService`, `GET /api/search/grep`) — the shallow one: a live, unindexed, case-insensitive literal substring scan over every paragraph, re-deriving text on every call (see below). No stemming, no diacritics folding, no relevance ranking (`score` is always absent) - always reflects the current corpus, at the cost of being the slowest option and bounded to the first 50,000 paragraphs scanned per call.
  - **Lucene** (`.search.lucene`, `GET /api/search/lucene`) — a real full-text index at paragraph granularity (same grain as the Milvus collections below), rebuilt on demand via `POST /api/admin/lucene/reindex` (not automatically - see `LuceneIndexService`) into `lucene.index.dir`. Language-aware: each `TeiFile`'s language (see below) gets its own analyzed field (`content_<lang>`/`head_<lang>`) using Lucene's built-in per-language stemmer/stopwords (`LuceneAnalyzers`) when one exists for that language, in addition to the always-present generic `content`/`head` fields (`TextbaseAnalyzer`, diacritics-folding, no stemming) that guarantee a diacritics-optional match regardless of language support.
  - **Vector/deep** (`.vector`, `GET /api/search/milvus`, `GET /api/search/ann`) — embedding-based similarity search via Milvus, unchanged from before (see the rest of this section).

  A document's language is detected once at import time from its own text (`LanguageDetectionService`, using the `lingua` library - no native/network dependency) and stored on `TeiFile.language` and every one of its `TeiDiv`/`TeiElem` rows, replacing the previous mechanism of guessing the language from the file's directory path (`TeiDirRepoImpl.getLanguageHint`, still used as a fallback if detection itself is inconclusive).

  Embeddings for the vector/deep mode come from an `Embedder` (`.vector.Embedder`); the production default (`@Primary`) is `qwen3EmbeddingEmbedder` (Qwen3-Embedding-4B via Ollama at `ollama.host:ollama.port`, `zmeu.local:11434` by default), which replaced the previous sentence-transformers `all-mpnet-base-v2` embedder (`StsEmbedder`, still available under its own bean name for existing callers). A second Ollama-backed embedder, `nomicEmbedder` (nomic-embed-text), is also configured but not wired in anywhere by default. `VectorSearchAvailability` checks the embedder and the production Milvus collection once at startup (`ApplicationReadyEvent`) and disables vector search gracefully - `/api/search/milvus` and `/api/search/ann` return empty results instead of throwing - for the rest of that run if either isn't reachable; relevant because the collection is now named after the new embedder (`tb_paras_qwen3_embedding_4b`) and needs the corpus re-embedded with it before it exists.
- **Fragments/quotations** (`.fragment`, `GET /quote/{divPath}?start=&end=`) — a Fragment is an arbitrary selection within a `TeiDiv`'s subtree, from a whole subchapter down to a single character, identified by the div's own path plus a start/end pair in "dot number notation" (`DotPath` - e.g. `2.1.15` = child 2, then child 1 of that, character 15 of its text; reuses the same 1-indexed element addressing `TeiElem.elemChild`/`_N` URL segments already use). `FragmentResolutionService` resolves both points (`DivService.childElem`, a small addition alongside the existing path-based resolution) and walks `DivService.getParagraphs()` to span one or several paragraphs, trimming the first/last to their offsets. Rendered as a standalone, embeddable "quote card" (`quote.html`/`quote.css` — big background quotation marks, minimal chrome, no site navigation) against plain text (not the decorated HTML pipeline — character offsets stay unambiguous, at the cost of not preserving inline markup in the quote).
- **Collections** (`.collection`, `.model.DivCollection`/`DivCollectionItem`, `/api/collections/*`) — a named grouping whose items are TeiDivs and/or Fragments. Two kinds, exposed very differently:
  - `/api/collections/mine/*` — real, persisted, per-`AppUser` collections (owner-authenticated, full CRUD). Every user gets an auto-created, non-deletable `favorites` collection at registration (`DivCollectionService.createFavoritesIfMissing`) — otherwise a normal collection, nothing special about its plumbing.
  - `/api/collections/system/*` — public, read-only, and never persisted at all: by-language (`TeiDivRepository.findOperaByLang`), by-author (`findOperaForAuthorStrId`), and by-repo (`TeiFile.repoName`, populated at import time from `TeiRepo.getRepoNameForFile` — which named sub-repo a file was imported from, for `CombinedTeiRepo` setups).
- **Read-only DAV export** (`.dav`, `/dav`) — projects the JPA-backed TEI hierarchy as a mountable `language/author/work` filesystem. `DavExportService` resolves virtual paths and applies language/author filters and the requested fragmentation frontier; `DavExportRenderer` serializes terminal fragments from their original TEI DOM as text, JSON, TEI XML, or standalone XHTML; `DavExportController` implements the read-only DAV protocol surface (`OPTIONS`, `PROPFIND`, `GET`, `HEAD`). No DAV files or directories are persisted, and mutation methods return HTTP 405.
- **Accounts** (`.security`, `.model.AppUser`) — real, persisted user accounts (not the old single hardcoded admin), needed to own Collections. Two sign-in paths:
  - Username/password: `POST /api/users/register`, then Spring Security's default session-based `formLogin` (`POST /login`) — `AppUserDetailsService` backs authentication with `AppUser` rows instead of the old `InMemoryUserDetailsManager`. The former single hardcoded admin account is migrated automatically on first boot (`AdminUserSeeder`, `ApplicationReadyEvent`) rather than lost.
  - Google Sign-In/One Tap (primary path — see `fragments/google-one-tap.html`, included on every page via `GlobalModelAttributes`): the widget POSTs a Google ID token to `POST /api/auth/google` (`GoogleAuthController`), which is verified server-side via Google's own `tokeninfo` endpoint (`GoogleTokenInfoVerifier` — no JWT/JOSE library needed, Google handles signature/key-rotation) and find-or-creates an `AppUser` by the token's `sub` claim (`GoogleAuthService`), then establishes a normal session. Disabled until `google.oauth.client-id` (`GOOGLE_OAUTH_CLIENT_ID`) is set to a real Google Cloud OAuth client id — deliberately no guessed default (see `feedback_no_silent_config_defaults`-style reasoning: an unset id must disable the feature, not silently accept tokens meant for a different Google app). A Google-only account has no local password (`AppUser.passwordHash` null) and can't use the username/password path.
  - A real shared Keycloak instance already exists in this ecosystem's infra (`keycloak.scriptorium.ro`) but isn't used here — evaluated and deliberately not adopted for this feature, since combining it with true Google One Tap UX (an on-page auto-prompt, not a redirect to a hosted login page) would mean bridging two separate flows for little benefit over the simpler direct approach above.
- **Persistence** — Spring Data JPA over MySQL (`.dao`, `.model`, `.dto`), with a local Caffeine + on-disk (`cache.dir`) cache layer (`.cache`).
- **Messaging** — Kafka (`.kafka`) for scheduled/async work (`.scheduled`).
- **Frontend** — the public app and the admin app are now separate, standalone repos (`textbase-ionic-ui`, `textbase-admin-ui`), built and deployed independently; this repo no longer builds or serves either SPA. Server-rendered Thymeleaf templates (`teidiv.html` etc.) still serve the crawlable/lynx-browseable book pages directly from here.
- **CLI** — `importTeiDivs` Gradle task / `TeiDivImporterCli` for bulk-importing TEI content outside the web server.

# Getting a quote

A Fragment's URL is `GET /quote/{divPath}?start={dotPath}&end={dotPath}` — the
div path is the normal `/author/opus/...` path any book/chapter page already
has, and `start`/`end` are "dot number notation" points (see `DotPath`):
1-indexed element-child navigation steps, then a trailing character offset
into whatever text that navigation lands on. There's no UI yet to pick a
quote by selecting text on the page (dot-paths are meant to be produced by
a future "select this, get a link" feature, not typed by hand) - but they're
simple enough to construct today if you already know roughly where the text
lives.

A real, verified example - Francis Bacon's "Of Gardens" (`bacon/of_gardens`)
opens with one of its most-quoted lines, nested two levels down from the
opus (a title-page wrapper div containing one `div2` sub-chapter, which is
where the essay's own paragraphs actually live):

```
GET /quote/bacon/of_gardens?start=7.4.0&end=7.4.85
```

`7` navigates to the essay's `div2` sub-chapter (the opus's 7th element
child), `4` navigates to its 4th child (the paragraph with the essay's
text), and `0`/`85` are the character range within that paragraph's plain
text. Renders as a standalone "quote card":

```html
<div class="quote-card">
    <blockquote class="quote-text">
        <p>GOD almighty first planted a garden: and indeed it is the purest of human pleasures. </p>
    </blockquote>
    <div class="quote-citation">
        <a href="/bacon/of_gardens">Francis Bacon</a>
        <span class="quote-citation-sep">&mdash;</span>
        <a href="/bacon/of_gardens">Of Gardens</a>
    </div>
</div>
```

— styled by `quote.css` with large, low-opacity `“`/`”` background glyphs
behind the text, Alegreya serif for the quote itself, PT Sans Narrow for the
citation line, and no site chrome (navbar/breadcrumb/TOC), so it drops
cleanly into an `<iframe>` on another page. `FragmentResolutionServiceTest`
and `FragmentControllerITest` cover the mechanics and corner cases in
detail (single-character selections, ranges spanning multiple paragraphs or
crossing a sub-chapter boundary, invalid/zero-length ranges, etc) against a
small self-contained multi-language fixture set
(`src/test/resources/testrepo-search`) rather than the full corpus.

# Read-only DAV export

The DAV endpoint exposes the corpus as a virtual filesystem rooted at `/dav`:

```text
/dav/
  {language}/
    {author}/
      {work or work.ext}
        {chapter or chapter.ext}
          {subchapter.ext}
```

For direct HTTP requests, configure an export with query parameters:

| Parameter | Required | Values | Default | Meaning |
| --- | --- | --- | --- | --- |
| `format` | no | `txt`, `json`, `xml`, `xhtml` | `txt` | File extension and serialization format. `xml` is the original TEI fragment; `xhtml` is a standalone XHTML document. |
| `fragmentation` | no | `1`, `1.1`, `1.1.1` | `1` | The deepest division exposed as files: work, chapter, or subchapter. |
| `lang` | no | a supported two-letter code such as `fr`, `ro`, or `zh` | all | Shows only works detected in that language. The language directory remains in the path. |
| `author` | no | the canonical author id used in Textbase URLs, such as `alecsandri` | all | Shows only that author's works. The author directory remains in the path. |

Examples:

```bash
# Inspect the mount root and its immediate children.
curl -i -X PROPFIND -H 'Depth: 1' \
  'http://localhost:8080/dav?format=txt&fragmentation=1'

# A French, chapter-level TEI XML export restricted to one author.
curl -i -X PROPFIND -H 'Depth: 1' \
  'http://localhost:8080/dav?format=xml&fragmentation=1.1&lang=fr&author=perrault'

# Fetch a file returned by PROPFIND. Keep its query string: DAV hrefs carry
# the mount configuration forward on every child URL.
curl 'http://localhost:8080/dav/fr/perrault/contes/peau_dane.xml?format=xml&fragmentation=1.1&lang=fr&author=perrault'
```

Do **not** use the query form as a DAV mount URL: several DAV clients discard
the query string when following child `href` values. That silently loses the
filters and falls back to a whole-corpus, whole-opus TXT export. Mount the
path-configured form instead:

```text
/dav/_export/{format}/{fragmentation}/{language-or-all}/{author-or-all}/
```

For example, this is a stable, French-only, Perrault-only, chapter-level TXT
mount URL:

```text
http://localhost:8080/dav/_export/txt/1.1/fr/perrault/
```

Use the literal `all` in either filter slot to disable that filter, for example
`/dav/_export/xml/1.1.1/all/all/` for every language and author. The configured
prefix is included in every returned DAV `href`, while the mounted contents
still begin with the normal `language/author/work` hierarchy. TXT terminal
resources always have a `.txt` extension (and likewise `.json`, `.xml`, or
`.xhtml` for the other formats).

`PROPFIND` supports DAV request depths `0` and `1`; deliberately unbounded
`Depth: infinity` walks are rejected with `403 propfind-finite-depth` to prevent
a single request from materializing the whole corpus.

Fragmentation selects a frontier, not a requirement that every work have that
many levels. At `fragmentation=1.1.1`, a work with subchapters becomes a tree of
subchapter files, while a work that ends at chapter level exposes that chapter
as a file. A work with no child divisions is itself a file at every setting.
Each terminal file contains its complete remaining TEI subtree, so shallower
branches do not lose text.

The endpoint is strictly read-only. It advertises and implements only
`OPTIONS`, `PROPFIND`, `GET`, and `HEAD`; `PUT`, `DELETE`, `MKCOL`, `COPY`,
`MOVE`, `PROPPATCH`, `LOCK`, and `UNLOCK` return `405 Method Not Allowed`.
There is no DAV write path into either the database or the source TEI repository.

# Running

Build/run profiles are Spring profiles (`-Dspring.profiles.active=...` or `SPRING_PROFILES_ACTIVE` env var), defined as `application-<profile>.properties` in `src/main/resources/`:

- `application.properties` — base config shared by all profiles (MySQL connection via `MYSQL_HOST`/`MYSQL_DB`/`MYSQL_USER`/`MYSQL_PASSWORD` env vars, port 8080, swagger paths, etc).
- `application-dev.properties` — local dev config (currently has one developer's hardcoded paths, e.g. `/home/petru/work/scriptorium-masters/build/`; adjust to your own machine, or add your own `application-<yourprofile>.properties` if you want a separate one alongside it).
- `application-ci.properties` — CI config for the `integration-deps` services: MySQL/Kafka on `mini.local`, and Milvus/embedder services on `zmeu.local`.
- `application-air.properties`, `application-int.properties`, `application-cli.properties` — other environment-specific profiles (a "dev workstation" variant, the `mini.local` integration deployment, and the CLI importer, respectively).

Local dev (backend only, skipping tests for speed):
```bash
$ ./gradlew bootRun -x test
```

Full run with a profile and auto-import of TEI content (`autoimport` is always appended in the `bootRun` task itself):
```bash
$ SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
$ ./gradlew -Pdev bootRun
$ PROFILE=ci rake run
```
> `-Pdev`, `-Pci`, and `-Pprod` select the corresponding Spring profile for `bootRun` and all Gradle test tasks.

# Tests

Tests use JUnit 5 (`useJUnitPlatform()`), plus Spring Boot test starters (web/kafka/restclient) and an H2 in-memory DB dependency.

Run only network-free unit tests under the CI profile:
```bash
$ ./gradlew -Pci unittest
```

Run both unit and external integration tests. This task defaults to the CI profile, and the external tests use the services managed by `/home/petru/work/scripts/docker/integration-deps`:
```bash
$ ./gradlew test
```

`./gradlew -Pci integrationTest` runs only the external integration tests. `rake test` delegates directly to the combined Gradle `test` task, and `rake ci` invokes it before building the image.

Equivalent to setting `SPRING_PROFILES_ACTIVE=dev`/`ci` before invoking Gradle (which still works too) — `-Pdev`/`-Pci` are shorter aliases. With neither flag, no profile is activated and only `application.properties` (base config) applies.

Docker image (built from `docker/dockerfile`, requires the jar already built via `./gradlew build`):
```bash
$ ./gradlew docker           # build local image editii/textbase-server:<version>
$ ./gradlew docker-publish   # also tag + push to the mini.local:5000 registry
```

History
===

Initially named scriptorium-repo. Just serve some TEIs.
There is also a project named TEI-publisher.
But: TEI is rather expensive. There are some TEI repos.
But docs: there are plenty. So our pipepline fodt -> tei -> web
might be on the long run, better.

- ODT format much easier to edit for starters, hence the originals in fodt (flat ODT)
- The idea is really to have a text database that you can access by URL, paragraph, word and letter.

GUI
---
- initially spring web, server-based
- for index/ and author/ we now have a separate Ionic/Angular app (textbase-ionic-ui, its own repo).
- site must remain crawlable and lynx-browseable
- right now, the teidiv.html template is server based. How to combine
the Ionic app under /app with the idea of having ordered URLS:
  - /author/work1/chapter1
- the /author is not that important. There are more relevant databases for famous people, like wikipedia etc.
- but /author/work/chapter is kind of the point of Textbase. That URL is the most proeminent way of querying Textbase.
- we need to have possible URLs like /author/work/chapter/div[0]/p[1] (an xpath subset that can identify fragments down to the letter)

Model economic
===
un url este mereu disponibil liber 
dar capacitatea de a naviga linear, de a citi o intreaga carte, nu e.
deci cautarea,care gaseste un url da.

dar inspectarea gratuita a unui url pune restrictii pentru vecinii sai imediati.

dupa un numar de next-uri gratuite pe zi, trebie sa cumperi abonament.

# Run Java server
```bash
$ gradle bootrun -Pprofile=dev,autoimport
```


# firefox

about:config

set security.fileuri.strict_origin_policy to false
in order to debug file:// html file with loading local resources.

# What is Textbase?

 A Database of wisdom, particularly addressable.
 Not a pile of ebooks: a very addressable vast space.
 Up to the letter.
 
 Ceea ce face particularitatea TB este adresabilitatea, dispozitia
 continutului interior pentru adresare si social sharing.
 
 Cele mai multe ebook stores vand ebookuri. TB ofera paragrafe, 
 in mare asta e ideea.
 
 De fapt TB nu vinde nimic, vinde abonamente.

# TODO

* address of a letter, of a paragraph, of a random range withing a book.
=======
# misc
de vazut bookmate.com seamana foarte mult cu ce fac eu.
vand epuburi.

# INTERNALS

Saxon is used for XML processing, rather than the internal Xerces.

# Changlog
0.4 editii-util is now part of textbase.
last stable 537c3a3f5cd2229d790b0b832b5fea130e7a0d16

Apache/Angular fix
The problem of having an angular app (which manages with the Router its own 
URL) deployed on apache is making the two correspond.
One way to do it is the following, says the internet:
```apache
RewriteEngine On
# If an existing asset or directory is requested go to it as it is
RewriteCond %{DOCUMENT_ROOT}%{REQUEST_URI} -f [OR]
RewriteCond %{DOCUMENT_ROOT}%{REQUEST_URI} -d
RewriteRule ^ - [L]

# If the requested resource doesn't exist, use index.html
RewriteRule ^ /index.html
```
But as it didnt work for us, we used a SpringBoot solution which works well.  

# FEATURES

## Ionic/Angular UI

* its own repo now (textbase-ionic-ui), built/deployed independently, still served at /app/*
* replaces and modernizes /index.html /author.html
* the site must remaing crawlable by search engines and Links

## Relocation

The Relocation table stores HTTP relocation, mainly for the situation where
an URL of a well-known chapter has changed and should be preserved.

Insert a Relocation like this:
```bash
O=/old/path \
N=/new/path \
curl -X POST  -H 'Content-Type:application/json' \
https://textbase.scriptorium.ro/api/drest/relocations/ -d '{ "oldPath" : "$O", "newPath": "$N" }'
```


## Admin interface
* its own repo now (textbase-admin-ui), built/deployed independently, still served at /admin, protected by basic auth, interacts with /api/admin/*

# PRESENTATION

# a shareable digital library
  - a text database, semantically marked up
  - shareable fragments 


# but pretty too
  - https://textbase.scriptorium.ro/mitru/povesti_despre_pacala_si_tandala/tilharul_boierit
  https://textbase.scriptorium.ro/perrault/contes/peau_dane


# urls

- urls mostly use div's heads.
- but, you can pass a number - which is the n'th (index) of the non-div para or whatever
- or an xpath fragment
