# sql/

Ad-hoc JDBC/Derby query tooling for any biblioteca profile - for humans
and coding agents who need to poke the live DB directly (schema checks,
sequence health, one-off diagnostics) without hand-compiling a Java class
each time. Uses Groovy's `groovy.sql.Sql` as the JDBC client, fed by
`/home/apps/secrets/load-env.sh` (see `AGENTS.md`) the same way every
other DB-touching tool in this repo gets its credentials.

## Usage

```bash
sql/run.sh <profile> <script.groovy> [args...]

# examples
sql/run.sh prod check_sequences.groovy
sql/run.sh zmeu check_sequences.groovy
```

`<profile>` is the pass-store stage under `biblioteca/` (`prod`, `zmeu`,
`dev`, ...). `run.sh` sources that profile's env, resolves the Derby
client + Groovy jars (reusing whatever Gradle already cached locally,
falling back to a Maven Central fetch into `~/.cache/biblioteca-sql-client`),
and runs the given `.groovy` file with `DB_URL` (and everything else
`load-env.sh` loaded) available as environment variables.

## Writing a new script

```groovy
import groovy.sql.Sql

def sql = Sql.newInstance(System.getenv("DB_URL"), "org.apache.derby.client.ClientAutoloadedDriver")
sql.eachRow("SELECT * FROM author") { row -> println row.str_id }
sql.close()
```

Table names can't be bound as JDBC parameters - build those with plain
string concatenation, not Groovy's `"...${table}..."` GString
interpolation (`Sql` treats an interpolated GString as parameterized SQL
and will try to bind the table name as a `?`, which Derby rejects).

**Never print `DB_URL` or any pass-store value** - it carries the DB
password as a connection attribute (`;user=...;password=...`). This
applies doubly to any tool (like Derby's own `dblook`) that embeds the
connection string in its own output by default - redact it before
saving/committing anything that tool produces.

## What's here

- `run.sh` - the runner described above.
- `check_sequences.groovy` - read-only safety check for every
  pooled-`SEQUENCE`-backed table (see
  `db.migration.V2__Switch_identity_columns_to_pooled_sequences`):
  flags any table whose sequence is seeded at or below its current
  max `id`. This is the tool that found the 2026-09-29 prod incident
  documented in the main README's "Persistence, caching, messaging"
  section - safe to run against prod at any time, it only reads.
- `ij` - Derby's own interactive SQL shell (`org.apache.derby.tools.ij`),
  auto-connected to `DB_URL` on startup. Same pass-store loader and jar
  resolution as `run.sh` (reuses whatever Gradle already cached, falling
  back to Maven Central) - use this instead of `run.sh` when you want a
  raw interactive `ij>` prompt rather than a canned Groovy script.

  ```bash
  sql/ij <profile>

  # examples
  sql/ij prod
  sql/ij zmeu
  ```
