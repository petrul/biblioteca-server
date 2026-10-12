package ro.editii.scriptorium.search.content

import org.apache.commons.io.output.NullWriter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cache.CacheManager
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.cache.CacheConf
import ro.editii.scriptorium.dao.RelocationRepository
import ro.editii.scriptorium.model.Relocation
import ro.editii.scriptorium.service.AdminService

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:pathContentResolverDb;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        // The deadlock test below suspends the pool: resolving must reuse
        // the caller's connection, as the old self-HTTP resolver could not
        // (see PathContentResolver).
        "spring.datasource.hikari.allow-pool-suspension=true",
        "spring.datasource.hikari.connection-timeout=5000",
])
@SpringBootTest(classes = [TestConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
class PathContentResolverTest {

    static final String PATH = "/alecsandri/legende/dumbrava_rosie"

    @Autowired PathContentResolver pathContentResolver
    @Autowired AdminService adminService
    @Autowired RelocationRepository relocationRepository
    @Autowired TransactionTemplate transactionTemplate
    @Autowired DataSource dataSource
    @Autowired CacheManager cacheManager

    @BeforeEach
    void beforeEach() {
        // just the one file these hits point into, not the full fixture set
        adminService.reimportFile("ro/Alecsandri-Poezii.xml", new NullWriter())
        // each test resolves from the database, not an earlier test's cache
        cacheManager.getCache(CacheConf.CACHE_HIT_TEXT).clear()
    }

    @Test
    void resolvesTheHitPathWhateverTheStoredHost() {
        // the stored hosts are the vectorizer's view of the server
        // (Docker's server:8080, bare metal's localhost:8080) - none need
        // answer from here
        ["http://server:8080", "http://localhost:1", "https://biblioteca.example"].each { host ->
            assert pathContentResolver.resolve(host + PATH) =~ /Dumbrava roșie/
        }
        assert pathContentResolver.resolve(PATH) =~ /Dumbrava roșie/
        assert pathContentResolver.resolve("http://server:8080${PATH}.txt") =~ /Dumbrava roșie/
    }

    @Test
    void aGoneSourceResolvesToNullAndLeavesTheCallersTransactionCommittable() {
        // as the search does: misses inside one wider transaction, which
        // must still commit (a rollback-only mark would throw here)
        transactionTemplate.executeWithoutResult {
            assert pathContentResolver.resolve("http://server:8080/alecsandri/legende/no_such_poem") == null
            assert pathContentResolver.resolve("http://server:8080/nobody/nothing") == null
            assert pathContentResolver.resolve("http://server:8080${PATH}") =~ /Dumbrava roșie/
        }
    }

    @Test
    void followsRelocations() {
        final oldPath = "/alecsandri/legende/renamed_away"
        transactionTemplate.executeWithoutResult {
            relocationRepository.save(Relocation.builder().oldPath(oldPath).newPath(PATH).build())
        }
        try {
            assert pathContentResolver.resolve("http://server:8080${oldPath}") =~ /Dumbrava roșie/
        } finally {
            transactionTemplate.executeWithoutResult { relocationRepository.deleteById(oldPath) }
        }
    }

    @Test
    void resolvedTextIsCachedByPathWhateverTheHost() {
        assert pathContentResolver.resolve("http://server:8080${PATH}") =~ /Dumbrava roșie/
        // a miss is not cached: a source imported later resolves at once
        assert pathContentResolver.resolve("http://server:8080/alecsandri/legende/no_such_poem") == null
        assert cacheManager.getCache(CacheConf.CACHE_HIT_TEXT).get("/alecsandri/legende/no_such_poem") == null

        // no database at all now: the same path under another host is a cache hit
        final pool = dataSource.unwrap(HikariDataSource).hikariPoolMXBean
        pool.suspendPool()
        try {
            assert pathContentResolver.resolve("http://localhost:8080${PATH}.txt") =~ /Dumbrava roșie/
        } finally {
            pool.resumePool()
        }
    }

    @Test
    void resolvesOnTheCallersConnectionWhileItIsHeld() {
        // The search request holds its connection while resolving every
        // hit. With the pool suspended once the caller has its connection,
        // anything needing another one waits out the pool timeout and fails
        // - as on an exhausted pool.
        final pool = dataSource.unwrap(HikariDataSource).hikariPoolMXBean
        final results = transactionTemplate.execute {
            relocationRepository.count() // the caller's connection is now bound
            pool.suspendPool()
            try {
                [pathContentResolver.resolve("http://server:8080${PATH}"),
                 pathContentResolver.resolve("http://server:8080/alecsandri/legende/no_such_poem")]
            } finally {
                pool.resumePool()
            }
        }
        assert results[0] =~ /Dumbrava roșie/
        assert results[1] == null
    }
}
