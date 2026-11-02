# Persistence, caching and messaging

## Database

Spring Data JPA over **PostgreSQL** (production and integration since Dusky
Discus, 2026-10-15) or **Apache Derby** in Network Server mode (still used
by some development profiles; the image is built from `docker/derby`). The
engine is chosen by the `DB_URL` prefix alone: `DbEngineDefaults` maps it to
the driver class and Hibernate dialect. Credentials ride in the URL (Derby
`;user=...;password=...`, PostgreSQL `?user=...&password=...`), and
`RunStuffOnStartup` redacts them before logging the URL.

Schema evolution is Hibernate `ddl-auto=update` for routine changes, plus
Flyway for fix-forward migrations:

- `src/main/java/db/migration` - V1-V7, Java;
- `src/main/groovy/db/migration` - V8 onwards, Groovy.

Use SQL for deterministic DDL and a Groovy `BaseJavaMigration` for
conditional or data-dependent repairs. Never rewrite an applied migration;
add a new version.

Notable migrations: `V2` switched IDENTITY to pooled SEQUENCE ids, so JDBC
insert batching actually batches. `V3` reseeds every sequence still inside
its table's used id range, which V2's create-only seeding could not repair.
That failure showed up as `23505` duplicate-key spam in production. Derby
has no `ALTER SEQUENCE ... RESTART WITH`, hence V3's drop-and-recreate.
`sql/check_sequences.groovy` re-checks the sequences on demand (see
`sql/README.md`), and `rake ij[profile]` opens a Derby `ij` shell.
`docs/prod-schema.sql` is a `dblook` snapshot of the former Derby production
schema (reference only).

## Caching

A Caffeine cache in front of an on-disk cache (`.cache` package) under
`WORK_DIR/cache`.

## Messaging

The `.kafka` and `.scheduled` packages handle asynchronous work, notably
notifying biblioteca-nestjs of new and reimported opera so it can vectorize
them. The real topic names (`biblioteca_*`) are in `KafkaProps.java`. Each
topic's message schema, producers and consumers are documented in
`src/main/resources/static/asyncapi.yml`, which is checked in and served
with the app.
