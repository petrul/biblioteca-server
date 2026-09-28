package ro.editii.scriptorium;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

public class TestUtils {
    public final static String TEI_ELEM = Util.TEI_ELEM;

    public static String randomString() {
        return randomString(20);
    }

    public static String randomString(int length) {
        return RandomStringUtils.randomAlphabetic(length);
    }

    public static String getTmpDir() {
        return System.getProperty("java.io.tmpdir");
    }

    public static void truncateAllTables(JdbcTemplate jt) {
        // DELETE FROM, not TRUNCATE + SET FOREIGN_KEY_CHECKS - see
        // AdminService.destroyAllExistingAndReimportAllTeis's own comment
        // for why (Derby has no FK-check-disable, and refuses to TRUNCATE
        // tei_elem outright due to its self-reference).
        jt.update("DELETE FROM tei_file_authors");
        jt.update("DELETE FROM author");
        jt.update("DELETE FROM " + TEI_ELEM);
        jt.update("DELETE FROM tei_file");
        jt.update("DELETE FROM relocation");

        assert countTableRows(jt, "author") == 0;
        assert countTableRows(jt, "tei_file_authors") == 0;
        assert countTableRows(jt, TEI_ELEM) == 0;
    }

    public static int countTableRows(JdbcTemplate jt, String tableName) {
        return jt.queryForObject(
                "select count(*) from " + tableName,
                Integer.class);
    }

    public static ObjectWriter jsonPp() {
        return new JsonMapper().writerWithDefaultPrettyPrinter();
    }

}
