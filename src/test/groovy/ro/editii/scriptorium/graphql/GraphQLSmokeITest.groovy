package ro.editii.scriptorium.graphql

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.fragment.FragmentResolutionService
import ro.editii.scriptorium.model.*
import ro.editii.scriptorium.search.content.ContentResolver
import ro.editii.scriptorium.search.grep.GrepSearchService
import ro.editii.scriptorium.search.lucene.LuceneIndexService
import ro.editii.scriptorium.security.AppUserRegistrationService
import ro.editii.scriptorium.service.*
import ro.editii.scriptorium.tei.TeiRepo
import ro.editii.scriptorium.toc.Toc
import ro.editii.scriptorium.vector.*

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration


/** HTTP smoke coverage with real JPA/REST handlers and mocked external dependencies. */
@Tag('integration-test')
@Timeout(90L)
@SpringBootTest(classes = GraphQLJpaTest.Application,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
        'spring.config.name=graphql-test',
        'spring.datasource.url=jdbc:h2:mem:graphql-smoke-itest;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1',
        'spring.datasource.driver-class-name=org.h2.Driver',
        'spring.jpa.database-platform=org.hibernate.dialect.H2Dialect',
        'spring.jpa.hibernate.ddl-auto=create-drop', 'spring.jpa.open-in-view=false',
        'spring.flyway.enabled=false', 'admin.users=admin', 'nestjs.internal-api-token=graphql-test-token',
        'spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true'])
@Import(GraphQLNativeMockConfig)
class GraphQLSmokeITest {
    @Value('${local.server.port}') int port
    @Autowired PlatformTransactionManager transactions
    @Autowired TeiDivRepository divs
    @PersistenceContext EntityManager em
    @Autowired GraphQLNativeMocks mocks
    TeiRepo teiRepo

    final client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    String strId, path
    Long divId
    static final PUBLIC = ['author', 'teiFile', 'teiElem', 'teiDiv', 'teiOpus', 'relocation',
            'mediaRef', 'authorMediaAssociation', 'divMediaAssociation', 'embeddingBatchStat', 'opusVectorizingStat']
    static final PRIVATE = ['appUser', 'userPreference', 'readingProgress', 'divCollection', 'divCollectionItem']

    @BeforeEach
    void fixtures() {
        mocks.reset()
        teiRepo = mocks.teiRepo
        strId = "smoke_${System.nanoTime()}"
        path = "$strId/work"
        new TransactionTemplate(transactions).executeWithoutResult {
            def author = Author.builder().strId(strId).lastName(strId).build()
            em.persist(author)
            def file = new TeiFile(filename: "${strId}.xml", title: 'Smoke work', language: Languages.EN,
                    repoName: 'smoke-repo', authors: [author])
            em.persist(file)
            def root = new TeiDiv(teiFile: file, head: "Smoke $strId", urlFragment: 'work',
                    lang: Languages.EN, xpath: '/tei:div[1]')
            em.persist(root)
            divId = root.id
            em.persist(new TeiDiv(teiFile: file, head: 'Chapter', parent: root,
                    urlFragment: 'chapter', xpath: '/tei:div[1]/tei:div[1]'))
        }
        mocks.byPath = { String requested -> assert requested == path; attachedDiv() }
        mocks.toc = { Long requested -> assert requested == divId; new Toc(attachedDiv()) }
        mocks.paragraphs = { TeiDiv requested, long offset, int size ->
            [new TeiElem(id: 9000000L, name: 'p', teiFile: attachedDiv().teiFile, parent: attachedDiv(),
                    urlFragment: 'p1', xpath: '/tei:div[1]/tei:p[1]', teiRepo: teiRepo)]
        }
        def xml = """<TEI xmlns="http://www.tei-c.org/ns/1.0"><teiHeader><fileDesc><titleStmt/>
            <publicationStmt/><sourceDesc/></fileDesc></teiHeader><text><body><div><head>Smoke work</head>
            <p>Smoke paragraph</p><div><head>Chapter</head></div></div></body></text></TEI>"""
        mocks.stream = { String filename -> new ByteArrayInputStream(xml.bytes) }
        mocks.paragraphText = 'Smoke paragraph'
    }

    private TeiDiv attachedDiv() {
        def div = divs.findById(divId).orElseThrow()
        div.teiRepo = teiRepo
        div
    }

    @Test
    void schemaAndEveryPublicRepositoryQueryWork() {
        def schema = query('{ __schema { queryType { fields { name } } mutationType { name } } }').__schema
        assert schema.mutationType == null
        def names = schema.queryType.fields*.name
        (PUBLIC + PRIVATE).each { assert names.containsAll([it, "${it}List".toString()]) }
        def lists = query('{ ' + PUBLIC.collect { "${it}List(size:1) { __typename }" }.join(' ') + ' }')
        PUBLIC.each { assert lists["${it}List"] instanceof List }
    }

    @Test
    void filtersAliasesFragmentsAndNestedEntitiesWork() {
        def result = query('''query($author:String!) {
            chosen: authorList(filter:{strId:$author},size:1) { ...AuthorFields }
            teiFileList(size:1) { filename authors { strId } }
        } fragment AuthorFields on Author { id strId lastName }''', [author: strId])
        assert result.chosen*.strId == [strId]
        assert result.teiFileList[0].authors[0].strId
    }

    @Test
    void catalogsMatchRestAndWorkPathsResolveThroughRealHandlers() {
        def result = query('''query($author:String!,$path:String!,$id:ID!,$coverId:String!) {
            authorCatalog(q:$author,size:2) { totalItems items { strId worksCount } }
            workCatalog(q:$author,size:2) { totalItems items { path head authors { strId } } }
            divByPath(path:$path) { path head }
            divDetails(id:$id) { path children { path head } }
            divToc(id:$id,size:2) { path head }
            divParagraphs(divId:$id,size:2,withContent:true) { text text_sha256 }
            coverMetadata(id:$coverId) { publisher }
        }''', [author: strId, path: path, id: divId.toString(), coverId: divId.toString()])
        assert result.authorCatalog.totalItems == rest("/api/authors/page?q=$strId&size=2").totalItems
        def restWorks = rest("/api/works?q=$strId&size=2")
        assert result.workCatalog.totalItems == restWorks.totalItems
        assert result.workCatalog.items*.path == restWorks.items*.path
        assert result.workCatalog.items[0].path == path
        assert result.authorCatalog.items[0].worksCount == 1
        assert result.divByPath.path == path
        assert result.divDetails.path == rest("/api/divs/$divId").path
        assert result.divDetails.children*.path == ["$path/chapter".toString()]
        assert result.divToc*.path.contains("$path/chapter".toString())
        assert result.divParagraphs[0].text == 'Smoke paragraph'
        assert result.divParagraphs[0].text_sha256.size() == 64
        assert result.coverMetadata.publisher == 'Biblioteca'
    }

    @Test
    void entitySearchUnionsResolveConcreteTypes() {
        def result = query('''query($q:String!) {
            searchAuthors(q:$q,limit:2) { data { __typename ... on AuthorDto { strId } } }
            searchDivHeads(q:$q,limit:2) { data { __typename ... on TeiDivDto { path } } }
        }''', [q: strId])
        assert result.searchAuthors[0].data == [__typename: 'AuthorDto', strId: strId]
        assert result.searchDivHeads[0].data == [__typename: 'TeiDivDto', path: path]
    }

    @Test
    void privateQueriesAndInvalidPagingReturnClassifiedErrors() {
        assert query('{ currentUser { authenticated } }').currentUser.authenticated == false
        PRIVATE.each { assertError("{ ${it}List(size:1) { __typename } }", 'UNAUTHORIZED') }
        ['myPreferences', 'myCollections { name }', 'myReadingProgress { opusPath }'].each {
            assertError("{ $it }", 'UNAUTHORIZED')
        }
        assertError('{ authorList(size:0) { id } }', 'BAD_REQUEST')
    }

    private Map query(String query, Map variables = [:]) {
        def response = graphql(query, variables)
        assert !response.errors : JsonOutput.toJson(response)
        response.data as Map
    }

    private void assertError(String query, String classification) {
        def errors = graphql(query).errors
        assert errors
        assert errors.every { it.extensions.classification == classification }
    }

    private Map graphql(String query, Map variables = [:]) {
        send('/graphql', JsonOutput.toJson([query: query, variables: variables]))
    }

    private Map rest(String path) { send(path) }

    private Map send(String path, String body = null) {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                .timeout(Duration.ofSeconds(30))
        if (path == '/graphql') request.header('X-Internal-Api-Token', 'Bearer graphql-test-token')
        if (body == null) request.GET()
        else request.header('Content-Type', 'application/json').POST(HttpRequest.BodyPublishers.ofString(body))
        def response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        assert response.statusCode() == 200 : response.body()
        new JsonSlurper().parseText(response.body()) as Map
    }
}
