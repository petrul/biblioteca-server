package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reseed every entity id sequence that is still inside its table's used id
 * range - the failure mode V2's create-only seeding cannot repair:
 *
 * V2 creates a sequence with START WITH max(id)+1 ONLY when the sequence does
 * not exist yet. But on a database where Hibernate's ddl-auto=update had
 * already created the named sequences when the entities switched from
 * IDENTITY to SEQUENCE, those sequences start at 1 and climb from there -
 * while the rows IDENTITY assigned before the switch already occupy the low
 * ids (tei_elem: rows up to id 54920 from the old identity counter, sequence
 * at 10001 and climbing). Every insert then lands on an existing primary key
 * - observed on the dev database as ~900 tolerated
 * "ERROR 23505: duplicate key ... on 'TEI_ELEM'/'TEI_FILE'/'AUTHOR'" per
 * autoimport sweep, each one aborting that file's import.
 *
 * This migration repairs such sequences in place: for each table, if the
 * sequence's next value is at or below max(id), ALTER SEQUENCE ... RESTART
 * WITH max(id)+1. Sequences already healthy (next value above max(id), e.g.
 * author_seq well past its rows after normal pooled allocation) are left
 * untouched - RESTART-ing them backward would be a needless change - and
 * tables that don't exist yet are skipped, same "fresh databases are left
 * alone" reasoning as V1/V2 (there ddl-auto=update creates the table and
 * its sequence itself, correctly seeded).
 *
 * Runs idempotently: after the reseed the sequence is above max(id), so a
 * re-run (or a hand-run retry) finds nothing to do.
 */
public class V3__Reseed_id_sequences_above_existing_rows extends BaseJavaMigration {

    private static final int ALLOCATION_SIZE = 50;

    // table -> sequence, mirroring V2 - the entities' own
    // @SequenceGenerator(sequenceName=...) pairs.
    private static final Map<String, String> SEQUENCES_BY_TABLE = new LinkedHashMap<>();
    static {
        SEQUENCES_BY_TABLE.put("tei_file", "tei_file_seq");
        SEQUENCES_BY_TABLE.put("tei_elem", "tei_elem_seq"); // shared with TeiDiv (SINGLE_TABLE inheritance)
        SEQUENCES_BY_TABLE.put("author", "author_seq");
        SEQUENCES_BY_TABLE.put("app_user", "app_user_seq");
        SEQUENCES_BY_TABLE.put("reading_progress", "reading_progress_seq");
        SEQUENCES_BY_TABLE.put("div_collection", "div_collection_seq");
        SEQUENCES_BY_TABLE.put("div_collection_item", "div_collection_item_seq");
        SEQUENCES_BY_TABLE.put("div_media", "div_media_seq");
        SEQUENCES_BY_TABLE.put("author_media", "author_media_seq");
        SEQUENCES_BY_TABLE.put("opus_vectorizing_stat", "opus_vectorizing_stat_seq");
        SEQUENCES_BY_TABLE.put("embedding_batch_stat", "embedding_batch_stat_seq");
    }

    @Override
    public void migrate(Context context) throws Exception {
        for (Map.Entry<String, String> entry : SEQUENCES_BY_TABLE.entrySet()) {
            final String table = entry.getKey();
            final String sequence = entry.getValue();
            if (!tableExists(context, table))
                continue;

            final Long nextValue = nextSequenceValue(context, sequence);
            if (nextValue == null)
                continue; // sequence does not exist - ddl-auto/V2 owns its creation

            final long maxId = maxId(context, table);
            if (nextValue <= maxId) {
                final long restartWith = maxId + 1;
                try (Statement statement = context.getConnection().createStatement()) {
                    // Derby has no ALTER SEQUENCE (ERROR 42X01), and its
                    // DROP SEQUENCE demands an explicit RESTRICT. A reseed
                    // is DROP ... RESTRICT + CREATE with START WITH strictly
                    // above max(id) - the same INCREMENT the entity mapping
                    // declares, so Hibernate's pooled optimizer keeps its
                    // block math.
                    statement.execute("DROP SEQUENCE " + sequence + " RESTRICT");
                    statement.execute("CREATE SEQUENCE " + sequence
                            + " START WITH " + restartWith
                            + " INCREMENT BY " + ALLOCATION_SIZE);
                }
            }
        }
    }

    /**
     * The value the sequence's NEXT allocation would return: CURRENTVALUE
     * (the last allocated value) plus the increment, or the STARTVALUE for
     * a sequence nobody has allocated from yet. Null when the sequence
     * itself does not exist.
     */
    private static Long nextSequenceValue(Context context, String sequence) throws Exception {
        final String upper = sequence.toUpperCase(Locale.ROOT);
        try (var statement = context.getConnection().prepareStatement(
                "SELECT CURRENTVALUE, STARTVALUE, INCREMENT FROM SYS.SYSSEQUENCES"
                        + " WHERE UPPER(SEQUENCENAME) = ?")) {
            statement.setString(1, upper);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    return null;
                final long increment = result.getLong("INCREMENT");
                final long start = result.getLong("STARTVALUE");
                final boolean currentWasNull = result.wasNull();
                return currentWasNull ? start : result.getLong("CURRENTVALUE") + increment;
            }
        }
    }

    private static boolean tableExists(Context context, String table) throws Exception {
        final DatabaseMetaData metadata = context.getConnection().getMetaData();
        try (ResultSet result = metadata.getTables(null, null, table.toUpperCase(Locale.ROOT), null)) {
            if (result.next()) return true;
        }
        try (ResultSet result = metadata.getTables(null, null, table, null)) {
            return result.next();
        }
    }

    private static long maxId(Context context, String table) throws Exception {
        try (Statement statement = context.getConnection().createStatement();
             ResultSet result = statement.executeQuery("SELECT MAX(id) FROM " + table)) {
            result.next();
            return result.getLong(1); // JDBC returns 0 for SQL NULL (empty table)
        }
    }
}
