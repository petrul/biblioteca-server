package ro.editii.scriptorium.mcp;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.graphql.ExecutionGraphQlResponse;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * MCP access to the server's read-only GraphQL API: an arbitrary read query
 * is executed in-process against the same spring-graphql engine POST /graphql
 * uses. Only queries are allowed (no mutation/subscription), and the
 * user-data query roots are denied outright - this tool set exposes the book
 * material only. The GraphQL layer additionally enforces its own
 * admin-gate on the private entity types (GraphQLJpaConfig.PRIVATE_TYPES),
 * so the deny-list here is defense in depth, not the only barrier.
 */
@Service
public class McpGraphqlTools {

    /**
     * Query roots that concern user information instead of the corpus - the
     * decapitalized private entity stems of GraphQLJpaConfig plus the
     * reader-workspace "my*" roots of GraphQLCatalogConfig. Public for the
     * Groovy tests (same-package static visibility across compilation units
     * does not resolve in groovyc).
     */
    public static final Set<String> DENIED_QUERY_ROOTS = Set.of(
            "appUser", "appUserList",
            "userPreference", "userPreferenceList",
            "readingProgress", "readingProgressList",
            "divCollection", "divCollectionList",
            "divCollectionItem", "divCollectionItemList",
            "currentUser", "myCollections", "myCollection",
            "myReadingProgress", "myWorkReadingProgress", "myPreferences");

    private final ObjectProvider<ExecutionGraphQlService> graphQlService;

    public McpGraphqlTools(ObjectProvider<ExecutionGraphQlService> graphQlService) {
        this.graphQlService = graphQlService;
    }

    @Tool(name = "graphql_query", description =
            "Run a read-only GraphQL query against the corpus schema (the same one POST /graphql " +
            "serves). Use introspection to discover types and fields. Corpus roots include " +
            "author/authorList, teiFile, teiElem, teiDiv/teiDivList, teiOpus, relocation, mediaRef " +
            "and the search/catalog roots (searchAuthors, searchDivHeads, workCatalog, divByPath...). " +
            "Only query operations are allowed; user-related roots are denied.")
    public Map<String, Object> graphqlQuery(
            @ToolParam(description = "The GraphQL query document to execute") String query,
            @ToolParam(required = false, description = "Query variables") Map<String, Object> variables,
            @ToolParam(required = false, description = "Operation name when the document has several operations") String operationName) {

        final Document document = validate(query);

        final var request = new DefaultExecutionGraphQlRequest(query, operationName, variables,
                null, UUID.randomUUID().toString(), Locale.getDefault());
        // Mutations are rejected by validate() above; the schema itself has no
        // mutation type either (see GraphQLSmokeITest.schemaAndEveryPublic...),
        // so only read queries can ever execute here.

        // execute() completes synchronously for this in-process call; the
        // reactive return type is the spring-graphql contract, not an async boundary.
        final ExecutionGraphQlResponse response =
                this.graphQlService.getObject().execute(request).block();

        assert response != null;
        final Map<String, Object> result = new LinkedHashMap<>(response.getExecutionResult().toSpecification());
        result.putIfAbsent("data", null);
        return result;
    }

    /**
     * Parse-level contract enforcement, thrown as a plain IllegalArgumentException
     * so the MCP client gets a clear tool error instead of an execution failure.
     * Public like {@link #DENIED_QUERY_ROOTS} for the Groovy tests.
     */
    public static Document validate(String query) {
        if (query == null || query.isBlank())
            throw new IllegalArgumentException("query must not be empty");

        final Document document;
        try {
            document = new Parser().parseDocument(query);
        } catch (RuntimeException e) {
            // graphql-java reports syntax errors as runtime exceptions
            // (InvalidSyntaxException/ParseCancellationException across versions)
            throw new IllegalArgumentException("malformed GraphQL query: " + e.getMessage());
        }

        final var denied = new HashSet<String>();
        for (var definition : document.getDefinitionsOfType(OperationDefinition.class)) {
            if (definition.getOperation() != OperationDefinition.Operation.QUERY)
                throw new IllegalArgumentException("only GraphQL query operations are allowed: "
                        + definition.getOperation().name().toLowerCase() + " is read-only surface only");
            for (var selection : definition.getSelectionSet().getSelections())
                if (selection instanceof Field field && DENIED_QUERY_ROOTS.contains(field.getName()))
                    denied.add(field.getName());
        }
        if (!denied.isEmpty())
            throw new IllegalArgumentException("query roots with user information are not available: "
                    + String.join(", ", denied.stream().sorted().toList()));
        return document;
    }
}
