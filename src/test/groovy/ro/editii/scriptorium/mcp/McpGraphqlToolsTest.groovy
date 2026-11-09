package ro.editii.scriptorum.mcp

// Same-package import pinned explicitly: groovyc incremental compilation can
// leave same-package classes unresolved and silently emit dynamic calls.
import ro.editii.scriptorium.mcp.McpGraphqlTools
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows

/**
 * Network-free validation tests for the MCP GraphQL tool's read-only,
 * corpus-only contract: queries allowed, everything user-related or
 * mutating rejected - no mocks needed, the validator is pure.
 */
class McpGraphqlToolsTest {

    @Test
    void corpusQueriesParseAndAreAllowed() {
        assert McpGraphqlTools.validate('{ authorList(size: 5) { strId lastName } }')
        assert McpGraphqlTools.validate('query T($id: ID!) { teiDiv(id: $id) { id head teiFile { title } } }')
        assert McpGraphqlTools.validate('{ searchDivHeads(q: "chapter title") { data } }')
        // fragments and aliases parse like on /graphql
        assert McpGraphqlTools.validate('query { teiDivList { a: head } } fragment X on TeiDiv { head }')
    }

    @Test
    void mutationAndSubscriptionOperationsAreDenied() {
        def error = assertThrows(IllegalArgumentException) {
            McpGraphqlTools.validate('mutation { anything }')
        }
        assert error.message == 'only GraphQL query operations are allowed: mutation is read-only surface only'
        assertThrows(IllegalArgumentException) { McpGraphqlTools.validate('subscription { anything }') }
        // even alongside a valid query
        assertThrows(IllegalArgumentException) {
            McpGraphqlTools.validate('query A { authorList { id } } mutation B { anything }')
        }
    }

    @Test
    void userInformationRootsAreDenied() {
        McpGraphqlTools.DENIED_QUERY_ROOTS.each { String root ->
            def error = assertThrows(IllegalArgumentException) {
                McpGraphqlTools.validate("{ $root { id } }")
            }
            assert error.message == "query roots with user information are not available: $root"
        }
        // and they don't slip through as passengers of combined queries
        assertThrows(IllegalArgumentException) {
            McpGraphqlTools.validate('{ authorList { id } readingProgressList { id } }')
        }
    }

    @Test
    void emptyAndMalformedQueriesAreRejected() {
        assertThrows(IllegalArgumentException) { McpGraphqlTools.validate(null) }
        assertThrows(IllegalArgumentException) { McpGraphqlTools.validate('   ') }
        assertThrows(IllegalArgumentException) { McpGraphqlTools.validate('{ author') }
        assertThrows(IllegalArgumentException) { McpGraphqlTools.validate('not graphql at all') }
    }
}
