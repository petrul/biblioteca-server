# Local development and test environment

These commands are for the `zmeu` development machine. Never print or commit
values loaded from the pass store.

## Load the zmeu environment

The environment loader is `/home/apps/secrets/load-env.sh`. Source it into the
same shell that will run Gradle:

```bash
source /home/apps/secrets/load-env.sh biblioteca/zmeu
```

It loads both encrypted secrets and plaintext configuration from the local
pass store into the current shell. `rake run[zmeu]` also loads this profile
automatically. Direct Gradle commands need the source step and an explicit
`-Pprofile=zmeu`.

## Run the application

```bash
rake run[zmeu]
```

This starts `bootRun` with the zmeu profile (and the normal `autoimport`
profile). Stop it with Ctrl-C.

## Run tests without touching a running server's database

Test tasks use the `ci` profile unless told otherwise: `rake test` and
`./gradlew test` run against `biblioteca_ci` on srv2's PostgreSQL (the
database TeamCity uses), and most Spring test contexts replace the datasource
with in-memory H2 anyway. Do not run tests with the `zmeu`
profile: its `DB_URL` is `biblioteca_zmeu`, the dev server's own data on the
same PostgreSQL.

```bash
rake "test[ci,LuceneSearchITest]"     # matching test classes, ci profile
```

Use `unittest` for the network-free suite and `test` when the external
integration services are available. Gradle defaults tests to the `ci` profile
unless `-Pprofile=zmeu` is supplied. Do not run zmeu tests against the shared
`biblioteca` database: tests can truncate tables and rebuild the Lucene index.

## REST API/client synchronization

Whenever a REST API is added, removed, renamed, or its contract changes, run
the reader's machine-aware `rake gen-client` task after the server is
available. Review and commit the regenerated client API artifacts together
with the REST API change.

## Database migration format

Use SQL migrations for deterministic schema changes and data-independent DDL.
Use a Groovy Flyway migration (a compiled class extending `BaseJavaMigration`)
for conditional repairs or data-dependent changes that need database metadata,
dynamic identifiers, or branching logic. The project already has Groovy support,
so no extra migration runtime is needed. Do not rewrite migrations that have
already been applied; add a new version instead.

## Stable work identity

Across all Biblioteca projects, refer to an opus by its canonical stable path
`authorId/opusId` (for example `shakespeare/hamlet`). Do not use database IDs,
display titles, import order, or fuzzy title matching for persisted links,
collections, bookmarks, search results, or featured-work lists.

## TeamCity snapshot verification

Before treating Docker snapshots as deployable, verify the corresponding
TeamCity build status; registry tags alone only prove that an image was
published, not that the latest build passed. On the TeamCity host, use the
REST API at `http://127.0.0.1:8111/teamcity/app/rest` with an access token
supplied at runtime. Never store or print that token. The relevant build
configurations are:

* `Textbase_BibliotecaServer_Build`
* `Textbase_TextbaseNestjs_Build`
* `Textbase_TextbaseReader_Build`
* `Biblioteca_Bibliotecacovers_Build`

The latest build for each must report `SUCCESS` before `run-dev.sh` is
considered a fully valid snapshot deployment. If a project manifest has a
newer `*-SNAPSHOT` version than the registry, the matching TeamCity build has
not yet published that snapshot (or failed); report deployment as blocked
instead of silently using an older tag.

## Groovy test mocks

In Spring Boot projects with Groovy tests, prefer native Groovy mocks, stubs,
and fakes over Mockito. Use closure/map coercion or native Groovy doubles and
register Spring test beans with `@TestBean` or an explicitly imported
`@TestConfiguration` where appropriate. Keep test-only beans out of component
scanning. Use Mockito only when a concrete limitation requires it; document
that reason in the test. See the README's Running tests section.
