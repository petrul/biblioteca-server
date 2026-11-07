#!/usr/bin/env bash
# Ad-hoc JDBC (PostgreSQL) query runner for any biblioteca profile - fed by the
# same pass-store env loader AGENTS.md documents for DB_URL. Lets a human
# or agent poke the live DB directly (schema checks, sequence health,
# one-off diagnostics) as a Groovy script instead of hand-compiling a
# throwaway Java class each time - see README.md here for an example.
#
# Usage: sql/run.sh <profile> <script.groovy> [args...]
#   sql/run.sh prod my_check.groovy
#   sql/run.sh zmeu my_check.groovy
#
# <profile> is the pass-store stage under biblioteca/ (prod, zmeu, ci, ...).
# The script sees DB_URL (and every other biblioteca/<profile> pass-store
# value) as an environment variable - NEVER print it or any value derived
# from it, it carries the DB password (?user=...&password=...).
set -euo pipefail

D=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PROFILE="${1:?usage: run.sh <profile> <script.groovy> [args...]}"
SCRIPT="${2:?usage: run.sh <profile> <script.groovy> [args...]}"
shift 2

source "${LOAD_ENV_SH:-$HOME/.password-store/load-env.sh}" "biblioteca/${PROFILE}"

# PostgreSQL driver + Groovy jars: reuse what Gradle already resolved on
# this machine if present, else fetch straight from Maven Central into a
# small local cache so repeat runs don't re-download.
CACHE_DIR="${SQL_CLIENT_CACHE_DIR:-$HOME/.cache/biblioteca-sql-client}"
mkdir -p "$CACHE_DIR"

resolve_jar() {
    local group="$1" artifact="$2" version="$3"
    local found
    found=$(find "$HOME/.gradle/caches/modules-2/files-2.1/${group}/${artifact}/${version}" \
        -iname "${artifact}-${version}.jar" 2>/dev/null | head -1)
    if [ -n "$found" ]; then
        echo "$found"
        return
    fi
    local dest="$CACHE_DIR/${artifact}-${version}.jar"
    if [ ! -f "$dest" ]; then
        curl -sL "https://repo1.maven.org/maven2/${group//./\/}/${artifact}/${version}/${artifact}-${version}.jar" -o "$dest"
    fi
    echo "$dest"
}

CP="$(resolve_jar org.postgresql postgresql 42.7.11)"
CP="$CP:$(resolve_jar org.apache.groovy groovy 5.0.6)"
CP="$CP:$(resolve_jar org.apache.groovy groovy-sql 5.0.2)"

java -cp "$CP" groovy.ui.GroovyMain "$D/$SCRIPT" "$@"
