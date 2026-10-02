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

## Run tests without using the shared development database

The zmeu profile's `DB_URL` points to the shared `biblioteca` Derby database.
Before running tests that may use the configured datasource, redirect `DB_URL`
to the dedicated `biblioteca-zmeu` database on the same Derby server. Preserve
the host, port, and connection attributes from the loaded URL without printing
them:

```bash
source /home/apps/secrets/load-env.sh biblioteca/zmeu
_db_origin="${DB_URL%%;*}"
_db_attrs="${DB_URL#"$_db_origin"}"
export DB_URL="${_db_origin%/*}/biblioteca-zmeu${_db_attrs}"
./gradlew -Pprofile=zmeu unittest --tests '*Lucene*' --tests '*TocIteratorTest'
```

For the full unit and integration suite, replace `unittest` with `test` in the
direct Gradle command above. Do not use `rake test[zmeu]` with this database
override: that task reloads the pass-store URL and would point back at the
shared `biblioteca` database. Some web integration tests override the
datasource with their own Derby in-memory database; leave those test overrides
intact.

Use `unittest` for the network-free suite and `test` when the external
integration services are available. Gradle defaults tests to the `ci` profile
unless `-Pprofile=zmeu` is supplied. Do not run zmeu tests against the shared
`biblioteca` database: tests can truncate tables and rebuild the Lucene index.

## REST API/client synchronization

Whenever a REST API is added, removed, renamed, or its contract changes, run
the reader's machine-aware `rake gen-client` task after the server is
available. Review and commit the regenerated client API artifacts together
with the REST API change.

## Stable work identity

Across all Biblioteca projects, refer to an opus by its canonical stable path
`authorId/opusId` (for example `shakespeare/hamlet`). Do not use database IDs,
display titles, import order, or fuzzy title matching for persisted links,
collections, bookmarks, search results, or featured-work lists.
