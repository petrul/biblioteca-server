package ro.editii.scriptorium.web

import org.apache.commons.io.FileUtils
import org.apache.commons.io.output.NullWriter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Lazy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.MultilangTeiRepoConfig
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.TextbaseServer
import ro.editii.scriptorium.client.TextbaseClient
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.dto.HitDto
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.service.AdminService
import ro.editii.scriptorium.vector.FakeEmbedderTestConfig
import ro.editii.scriptorium.vector.NetworkFreeVectorTestConfig

import java.nio.file.Files

import static ro.editii.scriptorium.TestUtils.TEI_ELEM

/**
 * Same fixture-loading convention as SearchITest (reimport real TEI
 * fixtures into H2, then exercise real REST endpoints through
 * TextbaseClient) but for /api/search/lucene - no Milvus/Ollama setup
 * needed here (FakeEmbedderTestConfig keeps the app context from touching
 * a real embedder at all, same reasoning as SearchITest), only a fresh
 * per-test Lucene index directory (see LuceneIndexService).
 *
 * Uses MultilangTeiRepoConfig's small, multi-language "testrepo-search"
 * fixture set (see that class's doc comment) rather than the shared
 * "testrepo" every other *ITest uses.
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:luceneSearchITestDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "milvus.address=mini.local:20112",
        "milvus.collection=test_tb_paras_qwen3_embedding_4b_lucene_itest_unused",
        "embeddings.host=mini.local",
        "embeddings.port=11200",
        "ollama.host=zmeu.local",
        "ollama.port=11434",
        "textbase.advertised.url=http://localhost:8080"
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // MultilangTeiRepoConfig listed after TestConfig so its teiRepo bean
        // overrides TestConfig's (allow-bean-definition-overriding above) -
        // verified by the exact-count assertion in beforeAll below.
        classes = [TextbaseServer.class, TestConfig.class, FakeEmbedderTestConfig.class,
                   MultilangTeiRepoConfig.class, NetworkFreeVectorTestConfig.class])
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LuceneSearchITest {

    @Autowired @Lazy TextbaseClient tbc
    @Autowired AdminService adminService
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired TeiFileRepository teiFileRepository

    static java.nio.file.Path indexDir

    @DynamicPropertySource
    static void luceneIndexDir(DynamicPropertyRegistry registry) {
        indexDir = Files.createTempDirectory("LuceneSearchITest-")
        registry.add("lucene.index.dir", { indexDir.toAbsolutePath().toString() })
    }

    @AfterAll
    static void deleteIndexDir() {
        FileUtils.deleteDirectory(indexDir.toFile())
    }

    // Neither test mutates data, so fixtures are imported and indexed once
    // for the whole class (PER_CLASS lifecycle) rather than per test method.
    @BeforeAll
    void beforeAll() {
        TestUtils.truncateAllTables(this.jdbcTemplate)
        this.adminService.reimportAllTeis(new NullWriter())

        assert countTableRows("author") > 0
        assert countTableRows(TEI_ELEM) > 0

        // Exact count - if MultilangTeiRepoConfig's teiRepo bean somehow
        // didn't win the override, this fails loudly instead of silently
        // running against the wrong (much bigger) fixture set.
        final teiFiles = this.teiFileRepository.findAll()
        assert teiFiles.size() == 6

        // Confirms language detection actually ran at import time (see
        // TeiFileDbService/LanguageDetectionService) instead of leaving
        // TeiFile.language null - each of the 6 fixtures is a different
        // language (see testrepo-search/).
        final languages = teiFiles*.language as Set
        assert languages == [Languages.RO, Languages.EN, Languages.FR, Languages.DE, Languages.ES, Languages.IT] as Set

        final indexed = this.tbc.post_api_admin_lucene_reindex()
        assert indexed > 0
    }

    @Test
    void searchLucene() {
        // Real, complete word from Francis Bacon's "Of Gardens" (see
        // testrepo-search/en) - a real full-text hit, not a stem/prefix
        // (see TextbaseAnalyzer - no stemming on the generic field).
        final resp = this.tbc.get_api_search_lucene("garden")

        assert resp.length > 0
        resp.each {
            assert it.type == HitDto.TYPES.lucene.name()
            assert !it.url.isBlank()
            // Not asserting content.contains("garden") here: a hit can match
            // purely via the (boosted) head/chapter-title field - see
            // LuceneIndexService - without the paragraph body itself
            // mentioning the term at all. That's correct search behavior.
        }
    }

    @Test
    void searchLuceneFindsDiacriticsFreeQueries() {
        // "romani" (plain ASCII) should still find the paragraph in the
        // Romanian fixture (testrepo-search/ro) that spells this "Români" -
        // TextbaseAnalyzer folds diacritics at index time, so the
        // stored/indexed term is already the plain-ASCII form.
        final resp = this.tbc.get_api_search_lucene("romani")

        assert resp.length > 0
    }

    @Test
    void searchGrep() {
        // Literal substring, case-insensitive, no stemming/diacritics
        // folding - matches "garden"/"Gardens"/"GARDEN" etc alike, but
        // would not fold "românește"-style diacritics (unlike Lucene above).
        final resp = this.tbc.get_api_search_grep("garden")

        assert resp.length > 0
        resp.each {
            assert it.type == HitDto.TYPES.grep.name()
            assert !it.url.isBlank()
            assert it.content.toLowerCase().contains("garden")
            assert it.score == null
        }
    }

    def countTableRows(tableName) {
        return this.jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class)
    }
}
