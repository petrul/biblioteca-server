package ro.editii.scriptorium.graphql;

import graphql.scalars.ExtendedScalars;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.idl.TypeRuntimeWiring;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import jakarta.servlet.http.HttpServletRequest;
import ro.editii.scriptorium.dto.AuthorDto;
import ro.editii.scriptorium.rest.*;

/** Entity-oriented REST read operations, reusing the existing handlers and services. */
@Configuration(proxyBeanMethods = false)
public class GraphQLCatalogConfig {
    @Bean
    DataFetcherExceptionResolver catalogErrors() {
        return DataFetcherExceptionResolver.forSingleError((error, env) -> {
            if (!(error instanceof ResponseStatusException status)) return null;
            ErrorType type = switch (status.getStatusCode().value()) {
                case 400 -> ErrorType.BAD_REQUEST;
                case 401 -> ErrorType.UNAUTHORIZED;
                case 403 -> ErrorType.FORBIDDEN;
                case 404 -> ErrorType.NOT_FOUND;
                default -> ErrorType.INTERNAL_ERROR;
            };
            return graphql.GraphqlErrorBuilder.newError(env).errorType(type)
                    .message(status.getReason() != null ? status.getReason() : status.getStatusCode().toString()).build();
        });
    }

    @Bean
    RuntimeWiringConfigurer catalogQueries(ObjectProvider<AuthorRestController> authors,
            ObjectProvider<WorkCatalogRestController> works, ObjectProvider<DivRestController> divs,
            ObjectProvider<DivCollectionRestController> collections,
            ObjectProvider<ReadingProgressRestController> progress,
            ObjectProvider<UserPreferencesRestController> preferences,
            ObjectProvider<SearchRestController> search, ObjectProvider<UserRestController> users) {
        return wiring -> wiring.scalar(ExtendedScalars.Json)
                .type(TypeRuntimeWiring.newTypeWiring("EntitySearchData").typeResolver(env ->
                        env.getSchema().getObjectType(env.getObject() instanceof AuthorDto ? "AuthorDto" : "TeiDivDto")))
                .type(TypeRuntimeWiring.newTypeWiring("Query")
                        .dataFetcher("authorDirectory", env -> authors.getObject().getAuthors(baseUri(), request()))
                        .dataFetcher("divCatalog", env -> divs.getObject().getAllTeiDivs(
                                integer(env, "page"), integer(env, "size"), baseUri()))
                        .dataFetcher("currentUser", env -> users.getObject().me(SecurityContextHolder.getContext().getAuthentication()))
                        .dataFetcher("authorCatalog", env -> authors.getObject().getAuthorPage(
                                integer(env, "page"), integer(env, "size"), env.getArgument("q"), baseUri(), request()))
                        .dataFetcher("workCatalog", env -> works.getObject().page(
                                integer(env, "page"), integer(env, "size"), env.getArgument("q"), env.getArgument("lang"), baseUri()))
                        .dataFetcher("authorDetails", env -> authors.getObject().getAuthor(env.getArgument("strId"), baseUri(), request()))
                        .dataFetcher("authorWorks", env -> authors.getObject().getOpera(env.getArgument("strId"), baseUri()))
                        .dataFetcher("authorMedia", env -> authors.getObject().getAllAuthorMedia())
                        .dataFetcher("divByPath", env -> {
                            // This existing REST handler has package visibility. Invoke its Spring proxy
                            // so its transaction and cover-enrichment behavior remain identical.
                            var method = ReflectionUtils.findMethod(DivRestController.class, "get_by_path", String.class, UriComponentsBuilder.class);
                            ReflectionUtils.makeAccessible(method);
                            return ReflectionUtils.invokeMethod(method, divs.getObject(), env.getArgument("path"), baseUri());
                        })
                        .dataFetcher("divDetails", env -> divs.getObject().get_id(id(env, "id"), baseUri(), request()))
                        .dataFetcher("divToc", env -> divs.getObject().get_id_toc(
                                id(env, "id"), integer(env, "page"), integer(env, "size"), baseUri(), request()))
                        .dataFetcher("divParagraphs", env -> divs.getObject().get_id_paras(
                                id(env, "divId"), integer(env, "page"), integer(env, "size"),
                                Boolean.TRUE.equals(env.getArgument("withContent")) ? "true" : null, baseUri(), request()))
                        .dataFetcher("coverMetadata", env -> divs.getObject().getCoverMetadata(
                                env.getArgument("id"), Boolean.TRUE.equals(env.getArgument("includeRawTei"))))
                        .dataFetcher("worksByLanguage", env -> collections.getObject().byLanguage(env.getArgument("lang"), baseUri()))
                        .dataFetcher("worksByAuthor", env -> collections.getObject().byAuthor(env.getArgument("authorStrId"), baseUri()))
                        .dataFetcher("worksByRepo", env -> collections.getObject().byRepo(env.getArgument("repoName"), baseUri()))
                        .dataFetcher("repoNames", env -> collections.getObject().repoNames())
                        .dataFetcher("featuredWorks", env -> collections.getObject().featured(baseUri()))
                        .dataFetcher("myCollections", env -> collections.getObject().mine(currentUser()))
                        .dataFetcher("myCollection", env -> collections.getObject().get(currentUser(), java.util.UUID.fromString(env.getArgument("id"))))
                        .dataFetcher("myReadingProgress", env -> progress.getObject().mine(currentUser()))
                        .dataFetcher("myWorkReadingProgress", env -> progress.getObject().one(currentUser(), env.getArgument("opusPath")))
                        .dataFetcher("myPreferences", env -> preferences.getObject().get(currentUser()))
                        .dataFetcher("searchAuthors", env -> search.getObject().searchAuthors(
                                searchText(env), limit(env), baseUri(), request()))
                        .dataFetcher("searchDivHeads", env -> search.getObject().searchDivHeads(searchText(env), limit(env), baseUri())));
    }

    private static HttpServletRequest request() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }

    private static UriComponentsBuilder baseUri() {
        return ServletUriComponentsBuilder.fromContextPath(request());
    }

    private static Authentication currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!new AuthenticationTrustResolverImpl().isAuthenticated(authentication))
            throw new AuthenticationCredentialsNotFoundException("Sign in to read your collections, preferences or reading progress");
        return authentication;
    }

    private static int integer(DataFetchingEnvironment env, String name) {
        return env.getArgument(name);
    }

    private static long id(DataFetchingEnvironment env, String name) {
        return Long.parseLong(env.getArgument(name));
    }

    private static String searchText(DataFetchingEnvironment env) {
        String q = env.getArgument("q");
        if (q.length() < 3) throw new IllegalArgumentException("q must contain at least 3 characters");
        return q;
    }

    private static int limit(DataFetchingEnvironment env) {
        int limit = integer(env, "limit");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return limit;
    }
}
