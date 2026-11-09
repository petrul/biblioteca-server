package ro.editii.scriptorium.mcp

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
import ro.editii.scriptorium.VersionProperties
import ro.editii.scriptorium.graphql.GraphQLJpaTest
import ro.editii.scriptorium.graphql.GraphQLNativeMockConfig
import ro.editii.scriptorium.graphql.GraphQLNativeMocks
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiElem
import ro.editii.scriptorium.model.TeiFile
import ro.editii.scriptorium.rest.InfoRestController
import ro.editii.scriptorium.toc.Toc

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * MCP wire-protocol smoke coverage for the tool set: the same H2/native-mock
 * harness as GraphQLSmokeITest, but speaking MCP JSON-RPC over the /mcp
 * streamable-HTTP endpoint - initialize handshake, tools/list, representative
 * tools per group (catalog, search, graphql), and the read-only,
 * no-user-information contract of the surface.
 */
@Tag('integration-test')
@Timeout(90L)
@SpringBootTest(classes = GraphQLJpaTest.Application,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
        'spring.config.name=graphql-test',
        'spring.ai.mcp.server.protocol=streamable',
        'spring.ai.mcp.server.annotation-scanner.enabled=false',
        'spring.ai.mcp.server.name=Biblioteca Server',
        'app.name=Biblioteca Server',
        'spring.datasource.url=jdbc:h2:mem:mcp-smoke-itest;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1',
        'spring.datasource.driver-class-name=org.h2.Driver',
        'spring.jpa.database-platform=org.hibernate.dialect.H2Dialect',
        'spring.jpa.hibernate.ddl-auto=create-drop', 'spring.jpa.open-in-view=false',
        'spring.flyway.enabled=false', 'admin.users=admin', 'biblioteca.internal-api-token=mcp-smoke-token',
        'spring.graphql.schema.locations=classpath:graphql/**/',
        'spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true'])
@Import([GraphQLNativeMockConfig, McpConfig, McpSearchTools, McpCatalogTools, McpTextTools, McpGraphqlTools,
        VersionProperties, InfoRestController])
class McpSmokeITest {
    @Value('${local.server.port}') int port
    @Autowired PlatformTransactionManager transactions
    @PersistenceContext EntityManager em
    @Autowired GraphQLNativeMocks mocks

    final client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    final json = new JsonSlurper()

    static final String TOKEN = 'Bearer mcp-smoke-token'

    String strId, path, sessionId
    Long divId

    @BeforeEach
    void fixtures() {
        mocks.reset()
        strId = "mcp_${System.nanoTime()}"
        path = "$strId/work"
        new TransactionTemplate(transactions).executeWithoutResult {
            def author = Author.builder().strId(strId).lastName(strId).build()
            em.persist(author)
            def file = new TeiFile(filename: "${strId}.xml", title: 'MCP work', language: Languages.EN,
                    repoName: 'mcp-repo', authors: [author])
            em.persist(file)
            def root = new TeiDiv(teiFile: file, head: "Mcp $strId", urlFragment: 'work',
                    lang: Languages.EN, xpath: '/tei:div[1]')
            em.persist(root)
            divId = root.id
            em.persist(new TeiDiv(teiFile: file, head: 'Chapter', parent: root,
                    urlFragment: 'chapter', xpath: '/tei:div[1]/tei:div[1]'))
        }
        def div = { em.find(TeiDiv, divId).with { teiRepo = mocks.teiRepo; it } }
        mocks.byPath = { String requested -> assert requested == path; div() }
        mocks.toc = { Long requested -> assert requested == divId; new Toc(div()) }
        mocks.paragraphs = { TeiDiv requested, long offset, int size ->
            [new TeiElem(id: 9000000L, name: 'p', teiFile: div().teiFile, parent: div(),
                    urlFragment: 'p1', xpath: '/tei:div[1]/tei:p[1]', teiRepo: mocks.teiRepo)]
        }
        def xml = """<TEI xmlns="http://www.tei-c.org/ns/1.0"><teiHeader><fileDesc><titleStmt/>
            <publicationStmt/><sourceDesc/></fileDesc></teiHeader><text><body><div><head>Mcp work</head>
            <p>Mcp paragraph</p><div><head>Chapter</head></div></div></body></text></TEI>"""
        mocks.stream = { String filename -> new ByteArrayInputStream(xml.bytes) }
        mocks.paragraphText = 'Mcp paragraph'
        sessionId = initializeSession()
    }

    private HttpResponse<String> post(Map body, String token = TOKEN) {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:$port/mcp"))
                .timeout(Duration.ofSeconds(30))
                .header('Content-Type', 'application/json')
                .header('Accept', 'application/json, text/event-stream')
        if (token != null) request.header('X-Internal-Api-Token', token)
        if (sessionId != null && body.method != 'initialize') request.header('Mcp-Session-Id', sessionId)
        def response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body))).build(),
                HttpResponse.BodyHandlers.ofString())
        response
    }

    private Map rpc(Map body, String token = TOKEN) {
        def response = post(body, token)
        // 200 = JSON-RPC response; 202 = notification accepted (empty body),
        // per the streamable-HTTP transport
        assert response.statusCode() == 200 || response.statusCode() == 202 :
                "${response.statusCode()}: ${response.body()}"
        if (!response.body()?.trim()) return [:]
        // streamable HTTP: the JSON-RPC response may come wrapped in a
        // server-sent event (text/event-stream) when the client accepts SSE
        if (response.headers().firstValue('Content-Type').orElse('').contains('text/event-stream'))
            return parseSseEvent(response.body())
        return json.parseText(response.body()) as Map
    }

    private Map parseSseEvent(String raw) {
        String data = ''
        raw.eachLine { line ->
            if (line.startsWith('data:')) data += line.substring('data:'.length()).strip()
        }
        assert data : "no data line in SSE event: ${raw.take(200)}"
        json.parseText(data) as Map
    }

    private String initializeSession() {
        sessionId = null
        def raw = post([jsonrpc: '2.0', id: 1, method: 'initialize', params: [
                protocolVersion: '2025-06-18', capabilities: [:], clientInfo: [name: 'mcp-smoke-test', version: '0']]])
        assert raw.statusCode() == 200 : "${raw.statusCode()}: ${raw.body()}"
        // the initialize response is plain JSON (not SSE-wrapped): streamable
        // HTTP servers answer the handshake directly
        def initialized = json.parseText(raw.body()) as Map
        assert initialized.result.serverInfo.name == 'Biblioteca Server' : initialized
        assert initialized.result.capabilities.containsKey('tools') : initialized
        sessionId = raw.headers().firstValue('Mcp-Session-Id').orElse(null)
        assert sessionId : 'the MCP server must issue a session id on initialize'
        rpc([jsonrpc: '2.0', method: 'notifications/initialized'])
        sessionId
    }

    private List toolsList() {
        (rpc([jsonrpc: '2.0', id: 2, method: 'tools/list', params: [:]]).result.tools) as List
    }

    private Map callTool(String name, Map arguments = [:]) {
        rpc([jsonrpc: '2.0', id: 3, method: 'tools/call', params: [name: name, arguments: arguments]])
    }

    private String toolText(Map call) {
        call.result.content[0].text
    }

    @Test
    void initializeHandshakeWorksOverStreamableHttp() {
        // fixtures() already initialized this session; this proves it stays
        // usable by listing tools through the same session
        assert toolsList().size() > 20
    }

    @Test
    void anonymousRequestsAreForbidden() {
        def savedSession = sessionId
        sessionId = null
        def response = post([jsonrpc: '2.0', id: 1, method: 'initialize', params: [
                protocolVersion: '2025-06-18', capabilities: [:], clientInfo: [name: 'anon', version: '0']]], null)
        assert response.statusCode() == 403 : "${response.statusCode()}: ${response.body()}"
        sessionId = savedSession
    }

    @Test
    void toolListIsTheCorpusSurfaceOnly() {
        def names = toolsList()*.name
        ['server_info', 'search_authors', 'search_div_heads', 'search_lucene', 'search_grep',
         'search_vector', 'search_ann', 'authors_page', 'author_details', 'author_works',
         'works_catalog', 'div_by_path', 'div_details', 'div_toc', 'div_paragraphs',
         'read_text', 'quote_fragment', 'graphql_query', 'relocation', 'lucene_status',
         'works_by_language', 'repo_names', 'featured_works', 'discover_works'].each {
            assert names.contains(it) : "expected tool $it in $names"
        }
        // no user information or admin surface anywhere in the list
        names.each { name ->
            assert !(name ==~ /(?i).*(user|preference|progress|password|google|admin|collection|login|register).*/) : name
        }
        toolsList().each { tool -> assert tool.description : "tool $tool.name must describe itself" }
    }

    @Test
    void serverInfoToolReportsTheBuildIdentity() {
        def call = callTool('server_info')
        assert !call.result.isError : call
        def info = json.parseText(toolText(call))
        assert info.appName == 'Biblioteca Server'
        assert info.version
    }

    @Test
    void catalogToolsServeTheCorpusThroughTheRestHandlers() {
        def call = callTool('div_paragraphs', [divId: divId, page: 0, size: 5, withContent: true])
        assert !call.result.isError : call
        def paragraphs = json.parseText(toolText(call))
        assert paragraphs[0].text == 'Mcp paragraph'
        assert paragraphs[0].text_sha256.size() == 64
    }

    @Test
    void searchToolsServeHits() {
        def call = callTool('search_div_heads', [q: strId, limit: 5])
        assert !call.result.isError : call
        def hits = json.parseText(toolText(call))
        assert hits*.data*.path.contains(path)
    }

    @Test
    void graphqlToolServesQueryRootsInProcess() {
        def call = callTool('graphql_query',
                [query: 'query($author:String!,$id:ID!) { authorList(filter:{strId:$author},size:1) { strId } ' +
                        'teiDiv(id:$id) { id head } }',
                 variables: [author: strId, id: divId.toString()]])
        assert !call.result.isError : call
        def data = json.parseText(toolText(call)).data
        assert data.authorList[0].strId == strId
        assert data.teiDiv.head == "Mcp $strId"
    }

    @Test
    void graphqlToolDeniesUserInformationAndMutations() {
        def denied = callTool('graphql_query', [query: '{ appUserList { id } }'])
        assert denied.result.isError : denied
        assert toolText(denied).contains('user information') : denied

        def mutating = callTool('graphql_query', [query: 'mutation { anything }'])
        assert mutating.result.isError : mutating
        assert toolText(mutating).contains('query operations') : mutating
    }

    @Test
    void shortSearchQueriesAreToolErrorsNotProtocolErrors() {
        def call = callTool('search_div_heads', [q: 'ab'])
        assert call.result.isError : call
        assert toolText(call).contains('at least 3 characters') : call
    }
}
