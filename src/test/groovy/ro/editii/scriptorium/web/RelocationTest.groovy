package ro.editii.scriptorium.web

import org.apache.commons.lang3.RandomStringUtils
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.jdbc.JdbcTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import ro.editii.scriptorium.GTestUtil
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.RelocationRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.Relocation
import ro.editii.scriptorium.service.DivService
import ro.editii.scriptorium.tei.TeifileParser

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

import static ro.editii.scriptorium.GTestUtil.p

// Split out of WebITest (was relocationWorks() there): this test never
// needed the shared 4-file testrepo fixture or Lucene indexing at all -
// it truncates all tables itself and builds its own single tiny opus via
// TeifileParser. Living inside WebITest only cost it (and everything that
// ran after it there) a full fixture reimport+Lucene-reindex via
// WebITest's own afterEach() restoring the shared fixture - see the class
// comment there. No @TestPropertySource lucene.* overrides here at all -
// build.gradle's tasks.withType(Test) already disables both
// lucene.autoindex.enabled and lucene.incremental.enabled for every test
// task by default (WebITest is the one deliberate exception), so a plain,
// minimal SpringBootTest config like this one gets indexing-free imports
// for free, the same way DivRestControllerTest/UrlContentResolverTest do.
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:derby:memory:relocationTestDb;create=true",
        "spring.datasource.driver-class-name=org.apache.derby.iapi.jdbc.AutoloadedDriver",
        "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create"
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TestConfig.class])
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration.class])
class RelocationTest {

    @LocalServerPort int port

    @Autowired AuthorRepository authorRepository
    @Autowired DivService divService
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired PlatformTransactionManager platformTransactionManager
    @Autowired RelocationRepository relocationRepository
    @Autowired RestTemplate restTemplateNoRedirect
    @Autowired TeiDivRepository teiDivRepository
    @Autowired TeifileParser parser

    @MockitoBean TextbaseEventsPublisher textbaseEventsPublisher

    String url(String path) {
        if (path.startsWith('/'))
            path = path.substring(1)

        return "http://localhost:${this.port}/$path"
    }

    @Test
    void relocationWorks() {
        2.times { time ->
            p "=" * 80
            p "= PASS ${time}"
            p "=" * 80

            final authorId = 'alecsandri'
            final opId = 'poezii'

            final tt = new TransactionTemplate(this.platformTransactionManager)
            def executor = Executors.newSingleThreadExecutor()

            TestUtils.truncateAllTables(this.jdbcTemplate)
            p this.authorRepository.findAll().strId
            assert this.authorRepository.getByStrId(authorId).empty
            assert JdbcTestUtils.countRowsInTable(this.jdbcTemplate, 'tei_elem') == 0
            assert JdbcTestUtils.countRowsInTable(this.jdbcTemplate, 'author') == 0

            final tei = GTestUtil.teiOf("Văsălie Alecsandri", """
                <div>
                    <head>Poezii</head>
                    <p>content</p>
                </div>
            """)
            this.parser.parse(tei, Languages.ES)

            // make sure data commited on test main thread is visible from another thread
            final mainThreadName = Thread.currentThread().name
            assert this.authorRepository.getByStrId(authorId).present
            final fut = executor.submit(() -> {
                assert mainThreadName != Thread.currentThread().name
                assert this.authorRepository.getByStrId(authorId).present
            })
            fut.get(1, TimeUnit.MINUTES)

            assert this.divService.getOpera(authorId).collect { it.urlFragment }.contains(opId)
            assert this.teiDivRepository
                    .findOperaForAuthorStrId(authorId)
                    .collect { it.urlFragment }
                    .contains(opId)

            final String nonExistentPath = "/$authorId/$opId/" + RandomStringUtils.randomAlphanumeric(200)
            final String relocationPath = RandomStringUtils.randomAlphanumeric(200);

            // this is the author page, it should exist

            final authorUrl = url("$authorId")
            assert this.restTemplateNoRedirect.getForEntity(authorUrl, String.class).body.containsIgnoreCase(authorId)

            // assert not teidiv points to that randomly generated 200-char string
            assert JdbcTestUtils.countRowsInTable(jdbcTemplate, 'relocation') == 0
            tt.execute(status -> {
                assert this.teiDivRepository.findAll().stream()
                        .noneMatch(it -> it.getCompletePath() == nonExistentPath)
            })

            // getting it by url should fail

            final nonExistentUrl = url(nonExistentPath)

            try {
                this.restTemplateNoRedirect.getForEntity(nonExistentUrl, String.class)
                Assertions.fail("call should fail")
            } catch (HttpClientErrorException e) {
                assert e.statusCode == HttpStatus.NOT_FOUND
                assert e.responseHeaders.get(HttpHeaders.LOCATION) == null
            }

            // insert relocation into db
            assert this.relocationRepository.findById(nonExistentPath).empty

            tt.execute((status) -> {
                this.relocationRepository.save(Relocation.builder()
                        .oldPath(nonExistentPath)
                        .newPath(relocationPath)
                        .build())
                status.flush()
            })

            assert this.relocationRepository.findAll().size() > 0

            // assert that db change is visible in other threads
            final Future<List<Relocation>> res = executor.submit(() -> {
                p "=> thread " + Thread.currentThread().name
                return tt.execute((TransactionStatus status) -> this.relocationRepository.findAll() as TransactionCallback<List<Relocation>>)
            } as Callable<List<Relocation>>)
            p res.get(2, TimeUnit.DAYS)
            p res.get().class
            assert !res.get().empty // size() > 0

            assert this.relocationRepository.findById(nonExistentPath).present
            final byId = this.relocationRepository.findById(nonExistentPath).get()
            assert byId.oldPath == nonExistentPath
            assert byId.newPath == relocationPath

            // re-get by url, this time no 404 should occur but rather 301 Re-Location:
            final resp = this.restTemplateNoRedirect.getForEntity(nonExistentUrl, String.class)
            assert resp.statusCode == HttpStatus.MOVED_PERMANENTLY
            final locationHeaders = resp.headers.get(HttpHeaders.LOCATION)
            assert locationHeaders.size() == 1
            assert locationHeaders.first() == url(relocationPath)
        }
    }
}
