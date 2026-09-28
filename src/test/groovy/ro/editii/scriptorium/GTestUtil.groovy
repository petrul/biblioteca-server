package ro.editii.scriptorium

import org.apache.commons.lang3.RandomStringUtils
import org.springframework.jdbc.core.JdbcTemplate

class GTestUtil {

    static String randomAlphabetic(int length) {
        return RandomStringUtils.randomAlphabetic(length)
    }

    static def tmpDir() {
        return System.getProperty("java.io.tmpdir") + "/" + randomAlphabetic(10)
    }

    static def iterable2list(Iterable<String> iterable) {
        return iterable2list(iterable, 10)
    }

    static def iterable2list(Iterable<String> iterable, int max_length) {
        def res = []
        int i = 0
        for (String c : iterable) {
            res << c
            if (++i >= max_length)
                break
        }
        res
    }

    static def p(args) {
        println(args)
    }

    static def countTableRows(JdbcTemplate jdbcTemplate, String tableName) {
        return jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class)
    }

    static String[] DERBY_INMEM_TEST_DB_PROPS = [
            "spring.datasource.url=jdbc:derby:memory:myDb;create=true",
            "spring.datasource.driver-class-name=org.apache.derby.jdbc.EmbeddedDriver",
            "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
            "spring.jpa.hibernate.ddl-auto = update"]

    static String teiOf(String content) {
        return teiOf("Alexandre Dumas", content)
    }

    static String teiOf(String author, String content) {
        return """<TEI xmlns="http://www.tei-c.org/ns/1.0">
                       <teiHeader>
                          <fileDesc>
                             <titleStmt>
                                <title>title</title>
                                <author>$author</author>
                             </titleStmt>
                          </fileDesc>
                       </teiHeader>
                       <text>                    
                          <body>
                             <head>file Title</head>
                             <author>Dumas,Alexandre</author>
                             <subtitle>1988</subtitle>
                            $content
               </body></text></TEI>"""
    }

}
