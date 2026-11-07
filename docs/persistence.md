# Persistence, caching and messaging

## Database

Spring Data JPA over **PostgreSQL** (production and integration since Dusky
Discus, 2026-10-15; the `zmeu` and `ci` profiles too). **Apache Derby**, the
former engine (0.9.9-0.9.14, in Network Server mode), is retired: Flyway 10+
dropped it, the migrations run on every start, and `DbEngineDefaults` stops
the server with that reason when `DB_URL` is a Derby URL. The tests run on
in-memory H2.
The engine is chosen by the `DB_URL` prefix alone: `DbEngineDefaults` maps it to
the driver class and Hibernate dialect. Credentials ride in the URL
(`?user=...&password=...`), and
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
had no `ALTER SEQUENCE ... RESTART WITH`, hence V3's drop-and-recreate.
`sql/run.sh` runs ad-hoc Groovy JDBC scripts against a profile's database
(see `sql/README.md`).
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
