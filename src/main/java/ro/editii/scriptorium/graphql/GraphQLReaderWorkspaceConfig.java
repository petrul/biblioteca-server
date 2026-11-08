package ro.editii.scriptorium.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.idl.TypeRuntimeWiring;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import ro.editii.scriptorium.dao.AppUserRepository;
import ro.editii.scriptorium.dao.ReaderWorkspaceStateRepository;
import ro.editii.scriptorium.collection.DivCollectionService;
import ro.editii.scriptorium.dto.DivCollectionDto;
import ro.editii.scriptorium.rest.DivCollectionRestController;
import ro.editii.scriptorium.model.AppUser;
import ro.editii.scriptorium.model.ReaderWorkspaceState;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

/** GraphQL read/write facade for the reader's synchronized workspace state. */
@Configuration(proxyBeanMethods = false)
public class GraphQLReaderWorkspaceConfig {
    private final ObjectMapper mapper = new ObjectMapper();

    @Bean
    RuntimeWiringConfigurer readerWorkspaceWiring(ReaderWorkspaceStateRepository states, AppUserRepository users,
                                                 ObjectProvider<DivCollectionService> collections,
                                                 ObjectProvider<DivCollectionRestController> collectionController) {
        return wiring -> wiring
                .type(TypeRuntimeWiring.newTypeWiring("Query")
                        .dataFetcher("myReaderState", env -> load(states, users))
                        .dataFetcher("mySavedSearches", env -> collections.getObject().listCollections(currentUser(users)).stream()
                                .filter(collection -> collection.getKind().name().equals("SEARCH"))
                                .map(collectionController.getObject()::toDto).toList()))
                .type(TypeRuntimeWiring.newTypeWiring("Mutation")
                        .dataFetcher("updateMyReaderState", env -> update(states, users, env))
                        .dataFetcher("saveMySearch", env -> saveSearch(collections.getObject(), collectionController.getObject(), users, env))
                        .dataFetcher("renameMyCollection", env -> collectionController.getObject().toDto(collections.getObject().renameCollection(currentUser(users), java.util.UUID.fromString(env.getArgument("id")), env.getArgument("name"))))
                        .dataFetcher("deleteMyCollection", env -> { collections.getObject().deleteCollection(currentUser(users), java.util.UUID.fromString(env.getArgument("id"))); return true; }));
    }

    @Bean
    DataFetcherExceptionResolver readerWorkspaceErrors() {
        return DataFetcherExceptionResolver.forSingleError((error, env) -> {
            if (error instanceof ConflictException) {
                return graphql.GraphqlErrorBuilder.newError(env).errorType(ErrorType.BAD_REQUEST)
                        .message(error.getMessage()).build();
            }
            if (error instanceof IllegalArgumentException) {
                return graphql.GraphqlErrorBuilder.newError(env).errorType(ErrorType.BAD_REQUEST)
                        .message(error.getMessage()).build();
            }
            return null;
        });
    }

    @Transactional(readOnly = true)
    private ReaderWorkspaceState load(ReaderWorkspaceStateRepository states, AppUserRepository users) {
        final AppUser user = currentUser(users);
        return states.findByOwnerUsername(user.getUsername()).orElseGet(() -> ReaderWorkspaceState.builder()
                .owner(user).revision(0L).updatedAt(new Timestamp(System.currentTimeMillis())).build());
    }

    @Transactional
    private ReaderWorkspaceState update(ReaderWorkspaceStateRepository states, AppUserRepository users,
                                        DataFetchingEnvironment env) {
        final AppUser user = currentUser(users);
        final long expected = ((Number) env.getArgument("expectedRevision")).longValue();
        final Map<String, Object> lastSearch = env.getArgument("lastSearch");
        final Map<String, Object> workspace = env.getArgument("workspace");
        validateSize(lastSearch, "lastSearch");
        validateSize(workspace, "workspace");
        final ReaderWorkspaceState state = states.findByOwnerUsername(user.getUsername())
                .orElseGet(() -> ReaderWorkspaceState.builder().owner(user).revision(0L).build());
        if (!state.getRevision().equals(expected)) {
            throw new ConflictException("reader state revision conflict; reload and retry");
        }
        state.setLastSearch(encode(lastSearch));
        state.setWorkspace(encode(workspace));
        state.setRevision(expected + 1);
        state.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        return states.save(state);
    }

    private DivCollectionDto saveSearch(DivCollectionService collections, DivCollectionRestController controller,
                                        AppUserRepository users, DataFetchingEnvironment env) {
        final var hits = ((java.util.List<Map<String, Object>>) (java.util.List<?>) (env.getArgument("hits") == null ? java.util.List.of() : env.getArgument("hits"))).stream()
                .map(hit -> new DivCollectionService.SearchHitToSave((String) hit.get("path"), (String) hit.get("snippet"), hit.get("score") == null ? null : ((Number) hit.get("score")).floatValue()))
                .toList();
        return controller.toDto(collections.saveSearch(currentUser(users), env.getArgument("name"), env.getArgument("query"), env.getArgument("params"), hits));
    }

    private AppUser currentUser(AppUserRepository users) {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!new AuthenticationTrustResolverImpl().isAuthenticated(authentication)) {
            throw new AuthenticationCredentialsNotFoundException("Sign in to synchronize reader state");
        }
        return users.findByUsername(authentication.getName()).orElseThrow();
    }

    private String encode(Object value) {
        if (value == null) return null;
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("reader state must be valid JSON"); }
    }

    private void validateSize(Object value, String name) {
        if (value == null) return;
        final String json = encode(value);
        if (json.length() > ReaderWorkspaceState.JSON_MAX_LENGTH)
            throw new IllegalArgumentException(name + " is too large");
    }

    static final class ConflictException extends RuntimeException {
        ConflictException(String message) { super(message); }
    }
}
