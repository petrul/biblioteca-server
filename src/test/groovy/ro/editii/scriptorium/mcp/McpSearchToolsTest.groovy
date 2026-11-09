package ro.editii.scriptorium.mcp

// Explicit same-package imports: groovyc's incremental compilation sometimes
// leaves same-package classes unresolved here; an explicit import forces
// static resolution at compile time instead of a dynamic property call.
import ro.editii.scriptorium.mcp.McpGraphqlTools
import ro.editii.scriptorium.mcp.McpSearchTools
import ro.editii.scriptorium.mcp.McpTextTools
import ro.editii.scriptorium.mcp.McpTextTools.TextFormat

import groovy.util.ProxyGenerator
import org.springframework.beans.factory.ObjectProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.util.UriComponentsBuilder
import ro.editii.scriptorium.rest.SearchRestController

/**
 * Native Groovy mocks throughout (per the house test convention): the MCP
 * search tools' wrapper contract only - argument validation the REST layer
 * gets from web binding, and delegation to the same SearchRestController
 * handlers the REST endpoints use.
 */
class McpSearchToolsTest {

    final List calls = []
    final search = ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass([
            searchAuthors: { String q, int limit, UriComponentsBuilder ucb, Object req ->
                calls << ['searchAuthors', q, limit]; []
            },
            searchDivHeads: { String q, int limit, List scope, UriComponentsBuilder ucb ->
                calls << ['searchDivHeads', q, limit, scope]; []
            },
            searchLucene: { String q, int limit, List scope, UriComponentsBuilder ucb ->
                calls << ['searchLucene', q, limit, scope]; []
            },
            searchGrep: { String q, int limit, List scope ->
                calls << ['searchGrep', q, limit, scope]; [['hit': true]]
            },
            searchVector: { String q, int limit, List scope, UriComponentsBuilder ucb ->
                calls << ['searchVector', q, limit, scope]; []
            },
    ], SearchRestController, [null, null, null, null] as Object[]) as SearchRestController

    final McpSearchTools tools = new McpSearchTools(provider(search))

    /** Groovy-native supplier mock, coerced from a map so getObject() resolves directly. */
    private static ObjectProvider provider(Object bean) {
        [getObject: { -> bean }, stream: { java.util.stream.Stream.empty() }] as ObjectProvider
    }

    @BeforeEach
    void requestContext() {
        def request = new MockHttpServletRequest()
        request.setRequestURI('/mcp')
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request))
    }

    @AfterEach
    void resetContext() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Test
    void grepDelegatesToTheRestHandlerWithArgumentsAsGiven() {
        assert tools.searchGrep('query', 10, List.of('shakespeare')) == [['hit': true]]
        assert calls == [['searchGrep', 'query', 10, List.of('shakespeare')]]
    }

    @Test
    void omittedArgumentsDelegateWithRestDefaults() {
        tools.searchGrep('query', null, null)
        assert calls == [['searchGrep', 'query', 10, null]]
    }

    @Test
    void shortQueriesAreRejectedWithoutCallingTheSearch() {
        [null, '', '  ', 'ab'].each { String q ->
            assertThrows(IllegalArgumentException) { tools.searchAuthors(q, 10) }
            assertThrows(IllegalArgumentException) { tools.searchDivHeads(q, 10, null) }
            assertThrows(IllegalArgumentException) { tools.searchLucene(q, 10, null) }
            assertThrows(IllegalArgumentException) { tools.searchGrep(q, 10, null) }
            assertThrows(IllegalArgumentException) { tools.searchVector(q, 10, null) }
        }
        assert calls.empty : "no search must run for short queries, got $calls"
    }

    @Test
    void annRequiresPathOrDivid() {
        assertThrows(IllegalArgumentException) { tools.searchAnn(null, null) }
        assert calls.empty
    }
}
