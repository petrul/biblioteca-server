package ro.editii.scriptorium.graphql

import groovy.util.ProxyGenerator
import jakarta.persistence.EntityManager
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.core.env.Environment
import ro.editii.scriptorium.collection.*
import ro.editii.scriptorium.dao.*
import ro.editii.scriptorium.rest.*
import ro.editii.scriptorium.security.AdminUsers
import ro.editii.scriptorium.service.*
import ro.editii.scriptorium.tei.TeiRepo
import ro.editii.scriptorium.vector.*
import ro.editii.scriptorium.search.content.ContentResolver

/** Native Groovy closure mocks shared by the HTTP tests; no Mockito or network services. */
class GraphQLNativeMocks {
    Closure byPath, opera, toc, paragraphs, stream
    String paragraphText
    final List<Object> coverRequests = []

    final TeiRepo teiRepo = [getStreamForName: { String filename -> stream(filename) }] as TeiRepo
    final DivService divService = ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass([
            getByPath: { String path -> byPath(path) },
            getOpera: { String author -> opera(author) },
            getToc: { Long id -> toc(id) },
            getParagraphs: { Object... args -> paragraphs(*args) }
    ], DivService, [null, null, null] as Object[]) as DivService
    final ControllerTool controllerTool = ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass([
            teiElemToString: { Object ignored -> paragraphText }
    ], ControllerTool, [divService] as Object[]) as ControllerTool
    final CoverEnrichmentRequests covers = ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass([
            requestMissing: { Object value -> coverRequests.add(value); null }
    ], CoverEnrichmentRequests, [null] as Object[]) as CoverEnrichmentRequests

    void reset() {
        byPath = opera = toc = paragraphs = stream = { Object... args ->
            throw new AssertionError("Unexpected fixture call: $args")
        }
        paragraphText = null
        coverRequests.clear()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class GraphQLNativeMockConfig {
    @Bean GraphQLNativeMocks nativeMocks() { new GraphQLNativeMocks() }
    @Bean ControllerTool controllerTool(GraphQLNativeMocks mocks) { mocks.controllerTool }
    @Bean VectorCollection vectorCollection() { [:] as VectorCollection }
    @Bean Embedder embedder() { [:] as Embedder }
    @Bean ContentResolver contentResolver() { [:] as ContentResolver }
    @Bean VectorSearchAvailability vectorAvailability() {
        ProxyGenerator.INSTANCE.instantiateAggregateFromBaseClass([
                checkAvailability: { }, recheckAvailability: { }, isAvailable: { false }
        ], VectorSearchAvailability, [null, null] as Object[]) as VectorSearchAvailability
    }
    @Bean AuthorRestController authors(Environment env, TeiDivRepository divs, AuthorRepository authors,
                                        AuthorMediaAssociationRepository media, GraphQLNativeMocks mocks) {
        new AuthorRestController(env, divs, authors, mocks.divService, media, mocks.covers)
    }
    @Bean WorkCatalogRestController works(TeiDivRepository divs, GraphQLNativeMocks mocks) {
        new WorkCatalogRestController(divs, mocks.covers)
    }
    @Bean DivRestController divisions(TeiDivRepository divs, EntityManager em,
                                     DivMediaAssociationRepository media, GraphQLNativeMocks mocks) {
        new DivRestController(divs, mocks.divService, mocks.controllerTool, em, mocks.teiRepo, mocks.covers, media)
    }
    @Bean DivCollectionService collectionsService(DivCollectionRepository collections,
                                                  DivCollectionItemRepository items, GraphQLNativeMocks mocks) {
        new DivCollectionService(collections, items, mocks.divService, null)
    }
    @Bean ReadingProgressService progressService(ReadingProgressRepository progress, GraphQLNativeMocks mocks) {
        new ReadingProgressService(progress, mocks.divService)
    }
    @Bean DivCollectionRestController collections(DivCollectionService service, AppUserRepository users,
                                                  TeiDivRepository divs, TeiFileRepository files, GraphQLNativeMocks mocks) {
        new DivCollectionRestController(service, users, divs, files, null, mocks.covers)
    }
    @Bean ReadingProgressRestController progress(ReadingProgressService service, AppUserRepository users) {
        new ReadingProgressRestController(service, users)
    }
    @Bean UserPreferencesRestController preferences(UserPreferenceRepository preferences) {
        new UserPreferencesRestController(preferences)
    }
    @Bean DbSearchService dbSearch(AuthorRepository authors, TeiDivRepository divs) {
        new DbSearchService(authors, divs, null, null, null)
    }
    @Bean SearchRestController search(Environment env, DbSearchService service, GraphQLNativeMocks mocks) {
        new SearchRestController(env, service, null, mocks.divService)
    }
    @Bean UserRestController userController(AppUserRepository users, AdminUsers admins) {
        new UserRestController(null, users, admins)
    }
}
