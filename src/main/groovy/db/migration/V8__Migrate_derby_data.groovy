package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Blob
import java.sql.Clob
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/**
 * Copies the corpus and the user data from the legacy Apache Derby
 * database into this one, the first time the application boots on
 * PostgreSQL (see the postgres migration).
 *
 * This is exactly the AGENTS.md case for a Groovy BaseJavaMigration: a
 * conditional, data-dependent change that branches on the target engine
 * and on the presence of a configured source database, none of which a
 * plain SQL migration can express.
 *
 * Conditional behaviour:
 *
 * - Target not PostgreSQL (the tests' embedded Derby databases, or a
 *   profile that has not switched yet): no-op, nothing to migrate.
 * - No DERBY_SOURCE_URL configured: no-op, logged - that environment
 *   populates itself from the TEI sources (autoimport) instead.
 * - DERBY_SOURCE_URL configured but unreachable: fail loudly. A silent
 *   skip would record the migration as applied with the data missing.
 * - A target table that already has rows: that table is skipped - this
 *   database was filled some other way (an import, an earlier run) and
 *   copying over it would be destructive.
 *
 * The Derby credentials ride in DERBY_SOURCE_USER / DERBY_SOURCE_PASSWORD
 * and never in the URL (which may be logged).
 *
 * Rows are copied with their ids preserved; every id sequence is then
 * reseeded strictly above the copied maximum (Hibernate's pooled
 * optimizers pick up from there). The copy runs inside one transaction
 * with session_replica_role=replica where the target user is a superuser
 * (foreign keys are checked after commit instead of per row - the table
 * order below is parent-first anyway, so non-superuser roles work too).
 */
class V8__Migrate_derby_data extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V8__Migrate_derby_data)

    private static final List<String> TABLES = [
            "app_user", "author", "media_ref", "author_media", "div_media",
            "_tei_file", "_tei_file_authors", "_tei_elem", "tei_opus",
            "user_preference", "div_collection", "div_collection_item",
            "reading_progress", "relocation",
            "embedding_batch_stat", "opus_vectorizing_stat",
    ]

    private static final int BATCH_SIZE = 500

    @Override
    void migrate(Context context) throws Exception {
        final Connection target = context.getConnection()
        final String product = target.getMetaData().getDatabaseProductName().toLowerCase()
        if (!product.contains("postgres")) {
            log.info("V8: target is {} - the Derby copy only runs on PostgreSQL", product)
            return
        }
        final String sourceUrl = System.getenv("DERBY_SOURCE_URL")
        if (sourceUrl == null || sourceUrl.isBlank()) {
            log.warn("V8: no DERBY_SOURCE_URL configured - leaving this database to the TEI import")
            return
        }
        final Properties sourceProps = new Properties()
        sourceProps.setProperty("user", System.getenv("DERBY_SOURCE_USER") ?: "")
        sourceProps.setProperty("password", System.getenv("DERBY_SOURCE_PASSWORD") ?: "")
        Class.forName("org.apache.derby.client.ClientAutoloadedDriver")

        boolean replicaRole = false
        try {
            try (Statement statement = target.createStatement()) {
                statement.execute("SET session_replica_role = replica")
                replicaRole = true
            } catch (Exception e) {
                log.info("V8: session_replica_role unavailable ({}); relying on parent-first copy order",
                        e.getClass().getSimpleName())
            }
            DriverManager.getConnection(sourceUrl, sourceProps).withCloseable { Connection source ->
                long total = 0
                for (String table : TABLES) {
                    if (count(target, table) > 0) {
                        log.info("V8: {} already has rows - skipped", table)
                        continue
                    }
                    final long copied = copyTable(source, target, table)
                    total += copied
                    log.info("V8: copied {} rows into {}", copied, table)
                }
                reseed(target)
                log.info("V8: done, {} rows copied, sequences reseeded", total)
            }
        } finally {
            if (replicaRole) {
                try (Statement statement = target.createStatement()) {
                    statement.execute("SET session_replica_role = DEFAULT")
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Rows copied from the source table into the (verified empty) target table. */
    private static long copyTable(Connection source, Connection target, String table) throws Exception {
        final List<String> columns = targetColumns(target, table)
        final Map<String, String> sourceTypes = sourceColumnTypes(source, table)
        for (String column : columns)
            if (!sourceTypes.containsKey(column.toUpperCase()))
                throw new IllegalStateException("V8: column " + column + " missing in the Derby " + table)

        final String select = columns.collect { '"' + it.toUpperCase() + '"' }.join(", ")
        final String insert = "INSERT INTO \"" + table + "\" ("
                + columns.collect { '"' + it + '"' }.join(", ") + ") VALUES ("
                + columns.collect { "?" }.join(", ") + ")"

        long copied = 0
        Statement reader = source.createStatement()
        reader.setFetchSize(1000)
        reader.withCloseable {
            PreparedStatement writer = target.prepareStatement(insert)
            writer.withCloseable {
                int batch = 0
                ResultSet rows = reader.executeQuery("SELECT " + select + " FROM \"" + table.toUpperCase() + "\"")
                rows.withCloseable {
                    while (rows.next()) {
                        for (int i = 1; i <= columns.size(); i++) {
                            Object value = rows.getObject(i)
                            if (value instanceof Blob)
                                value = value.getBytes(1L, (int) value.length())
                            else if (value instanceof Clob)
                                value = value.getSubString(1L, (int) value.length())
                            writer.setObject(i, value)
                        }
                        writer.addBatch()
                        if (++batch >= BATCH_SIZE) {
                            writer.executeBatch()
                            batch = 0
                        }
                        copied++
                    }
                    if (batch > 0)
                        writer.executeBatch()
                }
            }
        }
        return copied
    }

    private static long count(Connection connection, String table) throws Exception {
        Statement statement = connection.createStatement()
        statement.withCloseable {
            ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")
            rows.next()
            return rows.getLong(1)
        }
    }

    /** Target (PostgreSQL) column names in ordinal order, lowercase. */
    private static List<String> targetColumns(Connection target, String table) throws Exception {
        PreparedStatement query = target.prepareStatement(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position")
        query.withCloseable {
            query.setString(1, table)
            ResultSet rows = query.executeQuery()
            rows.withCloseable {
                List<String> columns = []
                while (rows.next())
                    columns.add(rows.getString(1))
                if (columns.isEmpty())
                    throw new IllegalStateException("V8: no target table " + table)
                return columns
            }
        }
    }

    /** Source (Derby) columns of the same table, keyed by uppercase name. */
    private static Map<String, String> sourceColumnTypes(Connection source, String table) throws Exception {
        DatabaseMetaData metadata = source.getMetaData()
        ResultSet rows = metadata.getColumns(null, null, table.toUpperCase(), null)
        rows.withCloseable {
            Map<String, String> columns = [:]
            while (rows.next())
                columns.put(rows.getString("COLUMN_NAME").toUpperCase(), rows.getString("TYPE_NAME"))
            return columns
        }
    }

    /** Every id sequence strictly above the copied maximum id. */
    private static void reseed(Connection target) throws Exception {
        List<String[]> pairs = []                    // [sequence, table]
        Statement sequences = target.createStatement()
        sequences.withCloseable {
            ResultSet rows = sequences.executeQuery(
                    "SELECT sequencename FROM pg_sequences WHERE schemaname = 'public'")
            rows.withCloseable {
                while (rows.next()) {
                    final String sequence = rows.getString(1)
                    if (sequence.endsWith("_seq") && hasIdColumn(target, sequence.substring(0, sequence.length() - 4)))
                        pairs.add([sequence, sequence.substring(0, sequence.length() - 4)] as String[])
                }
            }
        }
        Statement identity = target.createStatement()
        identity.withCloseable {
            ResultSet rows = identity.executeQuery("SELECT pg_get_serial_sequence('tei_opus', 'id')")
            rows.next()
            if (rows.getString(1) != null)
                pairs.add([rows.getString(1), "tei_opus"] as String[])
        }
        for (String[] pair : pairs) {
            final String sequence = pair[0], table = pair[1]
            Statement statement = target.createStatement()
            statement.withCloseable {
                statement.execute("SELECT setval('\"" + sequence + "\"',"
                        + " COALESCE((SELECT MAX(\"id\") FROM \"" + table + "\"), 0) + 1, false)")
                log.info("V8: reseeded {} for {}", sequence, table)
            }
        }
    }

    private static boolean hasIdColumn(Connection target, String table) throws Exception {
        PreparedStatement query = target.prepareStatement(
                "SELECT 1 FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ? AND column_name = 'id'")
        query.withCloseable {
            query.setString(1, table)
            ResultSet rows = query.executeQuery()
            rows.withCloseable { return rows.next() }
        }
    }
}
