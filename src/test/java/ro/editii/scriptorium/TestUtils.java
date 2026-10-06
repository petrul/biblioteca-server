package ro.editii.scriptorium;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

public class TestUtils {
    public final static String TEI_ELEM = Util.TEI_ELEM;
    public final static String TEI_FILE = "\"_tei_file\"";
    public final static String TEI_FILE_AUTHORS = "\"_tei_file_authors\"";

    public static String randomString() {
        return randomString(20);
    }

    public static String randomString(int length) {
        return RandomStringUtils.insecure().nextAlphabetic(length);
    }

    public static String getTmpDir() {
        return System.getProperty("java.io.tmpdir");
    }

    /**
     * The store's base address from a VECTORSTORE_URL that may carry its
     * collection as the final path segment (see VectorConfig.qdrantProdCollection:
     * the suffix wins over vector.collection). A test that wants its OWN
     * random-named collection must strip the suffix before using the value as
     * a REST base address or as vectorstore.address.
     */
    public static String vectorStoreBaseAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            final java.net.URI uri = java.net.URI.create(raw.contains("://") ? raw : "http://" + raw);
            final String[] segments = uri.getPath() == null ? new String[0] : uri.getPath().split("/");
            final String suffix = segments.length == 0 ? "" : segments[segments.length - 1];
            if (!suffix.isBlank()) {
                return uri.getScheme() + "://" + uri.getAuthority();
            }
        } catch (Exception ignored) {
            // Not parseable - hand it through untouched, exactly like
            // VectorConfig.qdrantProdCollection's own fallback.
        }
        return raw;
    }

    public static void truncateAllTables(JdbcTemplate jt) {
        // DELETE FROM, not TRUNCATE + SET FOREIGN_KEY_CHECKS - see
        // AdminService.destroyAllExistingAndReimportAllTeis's own comment
        // for why (Derby has no FK-check-disable, and refuses to TRUNCATE
        // tei_elem outright due to its self-reference).
        jt.update("DELETE FROM " + TEI_FILE_AUTHORS);
        jt.update("DELETE FROM author");
        jt.update("DELETE FROM tei_opus");
        jt.update("DELETE FROM " + TEI_ELEM);
        jt.update("DELETE FROM " + TEI_FILE);
        jt.update("DELETE FROM relocation");

        assert countTableRows(jt, "author") == 0;
        assert countTableRows(jt, TEI_FILE_AUTHORS) == 0;
        assert countTableRows(jt, TEI_ELEM) == 0;
    }

    public static int countTableRows(JdbcTemplate jt, String tableName) {
        if ("tei_file".equalsIgnoreCase(tableName)) tableName = TEI_FILE;
        if ("tei_file_authors".equalsIgnoreCase(tableName)) tableName = TEI_FILE_AUTHORS;
        if ("tei_elem".equalsIgnoreCase(tableName)) tableName = TEI_ELEM;
        return jt.queryForObject(
                "select count(*) from " + tableName,
                Integer.class);
    }

    public static ObjectWriter jsonPp() {
        return new JsonMapper().writerWithDefaultPrettyPrinter();
    }

}
