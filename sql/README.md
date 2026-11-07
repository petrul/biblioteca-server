# sql/

Ad-hoc JDBC tooling for any biblioteca profile's PostgreSQL database - for
humans and coding agents who need to look at the live DB directly (schema
checks, sequence health, one-off diagnostics) without hand-compiling a Java
class each time. Uses Groovy's `groovy.sql.Sql` as the JDBC client, fed by
the pass store's `load-env.sh` (see `AGENTS.md`) the same way every other
DB-touching tool in this repo gets its credentials.

## Usage

```bash
sql/run.sh <profile> <script.groovy> [args...]

# examples
sql/run.sh prod my_check.groovy
sql/run.sh zmeu my_check.groovy
```

`<profile>` is the pass-store stage under `biblioteca/` (`prod`, `zmeu`,
`ci`, ...). `run.sh` sources that profile's env, resolves the PostgreSQL
driver and the Groovy jars (reusing whatever Gradle already cached locally,
falling back to a Maven Central fetch into `~/.cache/biblioteca-sql-client`),
and runs the given `.groovy` file with `DB_URL` (and everything else
`load-env.sh` loaded) available as environment variables.

For an interactive prompt, use `psql` against the same database.

## Writing a new script

```groovy
import groovy.sql.Sql

def sql = Sql.newInstance(System.getenv("DB_URL"), "org.postgresql.Driver")
sql.eachRow("SELECT str_id FROM author ORDER BY str_id LIMIT 10") { row -> println row.str_id }
sql.close()
```

Table names can't be bound as JDBC parameters - build those with plain
string concatenation, not Groovy's `"...${table}..."` GString
interpolation (`Sql` treats an interpolated GString as parameterized SQL
and would try to bind the table name as a `?`).

**Never print `DB_URL` or any pass-store value** - it carries the DB
password (`?user=...&password=...`). Redact it from anything a tool prints
or saves before committing it.

Apache Derby, the engine before Dusky Discus (2026-10-15), is retired; its
`ij` shell and the Derby-only `check_sequences.groovy` are gone with it.
