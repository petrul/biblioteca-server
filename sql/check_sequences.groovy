// Sequence-safety check: for every entity table that uses a pooled
// SEQUENCE (see db.migration.V2__Switch_identity_columns_to_pooled_sequences),
// compares the table's actual max(id) against the sequence's current
// value. "current <= max" means the sequence is seeded inside
// already-used id space - the next insert(s) will hit a duplicate-key
// error (SQLSTATE 23505) sooner or later.
//
// This is exactly the check that found the 2026-09-29 prod incident
// (README.md's "Persistence, caching, messaging" section /
// "Open - correctness" bug entry): opus_vectorizing_stat self-healed
// under load, but app_user_seq and author_media_seq were sitting unsafe
// and hadn't been hit by traffic yet.
//
// Usage: sql/run.sh <profile> check_sequences.groovy
//
// Read-only - safe to run against any profile, including prod, at any
// time. It does not fix anything; see the README for how the 2026-09-29
// incident was fixed manually (DROP + CREATE SEQUENCE, Derby has no
// ALTER SEQUENCE ... RESTART WITH).

import groovy.sql.Sql

def url = System.getenv("DB_URL")
def sql = Sql.newInstance(url, "org.apache.derby.client.ClientAutoloadedDriver")

// table -> sequence, from V2__Switch_identity_columns_to_pooled_sequences
def seqByTable = [
    tei_file               : "tei_file_seq",
    tei_elem                : "tei_elem_seq",
    author                   : "author_seq",
    app_user                 : "app_user_seq",
    reading_progress         : "reading_progress_seq",
    div_collection           : "div_collection_seq",
    div_collection_item      : "div_collection_item_seq",
    div_media                : "div_media_seq",
    author_media             : "author_media_seq",
    opus_vectorizing_stat    : "opus_vectorizing_stat_seq",
    embedding_batch_stat     : "embedding_batch_stat_seq",
]

println "table".padRight(24) + "max(id)".padRight(10) + "count".padRight(10) + "seq_current".padRight(14) + "status"
seqByTable.each { table, seqName ->
    def row = sql.firstRow("SELECT MAX(id) AS maxid, COUNT(*) AS cnt FROM " + table)
    def maxId = (row.maxid ?: 0) as long
    def cnt = row.cnt as long
    def seqRow = sql.firstRow("SELECT CURRENTVALUE FROM SYS.SYSSEQUENCES WHERE UPPER(SEQUENCENAME) = ?", [seqName.toUpperCase()])
    def current = seqRow ? (seqRow.CURRENTVALUE as long) : null
    def status = current == null ? "NO SEQUENCE" : (current <= maxId ? "UNSAFE (current <= max)" : "ok")
    println table.padRight(24) + "${maxId}".padRight(10) + "${cnt}".padRight(10) + "${current}".padRight(14) + status
}
sql.close()
