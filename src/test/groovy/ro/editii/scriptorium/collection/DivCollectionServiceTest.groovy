package ro.editii.scriptorium.collection

import org.apache.commons.io.output.NullWriter
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.MultilangTeiRepoConfig
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.BibliotecaServer
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.model.AppUser
import ro.editii.scriptorium.model.DivCollectionItem
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.DivCollection
import ro.editii.scriptorium.security.AppUserRegistrationService
import ro.editii.scriptorium.service.AdminService
import ro.editii.scriptorium.service.DivService
import ro.editii.scriptorium.tei.TeiRepo
import ro.editii.scriptorium.vector.FakeEmbedderTestConfig
import ro.editii.scriptorium.vector.NetworkFreeVectorTestConfig

import static ro.editii.scriptorium.TestUtils.TEI_ELEM

/**
 * Uses MultilangTeiRepoConfig's small fixture set. Exercises
 * DivCollectionService directly (not through HTTP/Authentication) since
 * that's where the real business logic and edge cases live - the REST
 * controller layer above it is thin delegation + DTO mapping, and the
 * general "HTTP + Spring Security auth" wiring is already covered by
 * AppUserAuthTest.
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:divCollectionServiceTestDb;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "vectorstore.address=http://mini.local:20112",
        "vector.collection=test_tb_paras_qwen3_embedding_4b_collection_test_unused",
        "embeddings.host=mini.local",
        "embeddings.port=11200",
        "ollama.host=zmeu.local",
        "ollama.port=11434",
        "textbase.advertised.url=http://localhost:8080"
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [BibliotecaServer.class, TestConfig.class, FakeEmbedderTestConfig.class,
                   MultilangTeiRepoConfig.class, NetworkFreeVectorTestConfig.class])
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DivCollectionServiceTest {

    @Autowired DivCollectionService divCollectionService
    @Autowired AppUserRegistrationService registrationService
    @Autowired AdminService adminService
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired TeiDivRepository teiDivRepository
    @Autowired DivService divService
    @Autowired TeiRepo teiRepo

    TeiDiv opus
    int leafStep
    AppUser user

    @BeforeAll
    void beforeAll() {
        TestUtils.truncateAllTables(this.jdbcTemplate)
        this.adminService.reimportAllTeis(new NullWriter())
        assert this.jdbcTemplate.queryForObject("select count(*) from " + TEI_ELEM, Integer.class) > 0

        final english = this.teiDivRepository.findOperaByLang(Languages.EN, PageRequest.of(0, 5))
        this.opus = english.content.first()
        this.opus.setTeiRepo(this.teiRepo)

        for (int n = 1; n <= 30; n++) {
            try {
                final child = this.divService.childElem(this.opus, n)
                if (!child.isDiv()) {
                    this.leafStep = n
                    break
                }
            } catch (Exception ignored) {
                break
            }
        }
        assert this.leafStep > 0

        this.user = this.registrationService.register("collector_" + System.nanoTime(), "correcthorsebattery")
    }

    @Test
    void favoritesIsAutoCreatedAtRegistration() {
        final collections = this.divCollectionService.listCollections(this.user)
        assert collections.size() == 1
        assert collections.first().name == DivCollection.FAVORITES_NAME
        assert collections.first().isFavorites
    }

    @Test
    void addsADivToFavorites() {
        final item = this.divCollectionService.addDiv(this.user, DivCollection.FAVORITES_NAME, this.opus.completePath)

        assert item.kind == DivCollectionItem.Kind.DIV
        assert item.divPath == this.opus.completePath

        final favorites = this.divCollectionService.getCollection(this.user, DivCollection.FAVORITES_NAME)
        assert favorites.items.any { it.id == item.id }
    }

    @Test
    void createsACustomCollectionAndAddsAValidFragment() {
        final name = "quotes_" + System.nanoTime()
        this.divCollectionService.createCollection(this.user, name)

        final item = this.divCollectionService.addFragment(
                this.user, name, this.opus.completePath, "${this.leafStep}.0", "${this.leafStep}.5")

        assert item.kind == DivCollectionItem.Kind.FRAGMENT
        assert item.fragmentStart == "${this.leafStep}.0"
        assert item.fragmentEnd == "${this.leafStep}.5"

        final collection = this.divCollectionService.getCollection(this.user, name)
        assert collection.items.size() == 1
    }

    @Test
    void rejectsAnInvalidFragmentWithoutStoringIt() {
        final name = "badquotes_" + System.nanoTime()
        this.divCollectionService.createCollection(this.user, name)

        final ex = shouldFail {
            // reversed range - invalid, see FragmentResolutionService
            this.divCollectionService.addFragment(this.user, name, this.opus.completePath, "${this.leafStep}.5", "${this.leafStep}.0")
        }
        assert ex != null

        final collection = this.divCollectionService.getCollection(this.user, name)
        assert collection.items.isEmpty()
    }

    @Test
    void rejectsADuplicateCollectionName() {
        final ex = shouldFail { this.divCollectionService.createCollection(this.user, DivCollection.FAVORITES_NAME) }
        assert ex.message.contains("already")
    }

    @Test
    void refusesToDeleteFavorites() {
        final ex = shouldFail { this.divCollectionService.deleteCollection(this.user, DivCollection.FAVORITES_NAME) }
        assert ex.message.contains("cannot be deleted")
    }

    @Test
    void removesAnItemAndDeletesACustomCollection() {
        final name = "toRemove_" + System.nanoTime()
        this.divCollectionService.createCollection(this.user, name)
        final item = this.divCollectionService.addDiv(this.user, name, this.opus.completePath)

        this.divCollectionService.removeItem(this.user, name, item.id)
        assert this.divCollectionService.getCollection(this.user, name).items.isEmpty()

        this.divCollectionService.deleteCollection(this.user, name)
        final ex = shouldFail { this.divCollectionService.getCollection(this.user, name) }
        assert ex.message.contains("no such collection")
    }

    @Test
    void collectionsAreIsolatedPerUser() {
        final otherUser = this.registrationService.register("other_" + System.nanoTime(), "correcthorsebattery")
        final name = "private_" + System.nanoTime()
        this.divCollectionService.createCollection(this.user, name)

        final ex = shouldFail { this.divCollectionService.getCollection(otherUser, name) }
        assert ex.message.contains("no such collection")

        // The other user's own favorites is separate too.
        assert this.divCollectionService.listCollections(otherUser).size() == 1
    }

    static Exception shouldFail(Closure closure) {
        try {
            closure.call()
        } catch (Exception e) {
            return e
        }
        throw new AssertionError("expected an exception but none was thrown")
    }

    @Test
    void collectionsHaveUuidIdsAndAKind() {
        final fav = this.divCollectionService.createFavoritesIfMissing(this.user)
        assert fav.id instanceof UUID
        assert fav.kind == CollectionKind.FAVORITES
        final mine = this.divCollectionService.createCollection(this.user, "kinds_" + System.nanoTime())
        assert mine.id instanceof UUID
        assert mine.kind == CollectionKind.USER
        assert this.divCollectionService.getCollection(this.user, mine.id).name == mine.name
    }

    @Test
    void userCollectionCanBeRenamedAndItsItemsReordered() {
        final col = this.divCollectionService.createCollection(this.user, "sortable_" + System.nanoTime())
        final a = this.divCollectionService.addDiv(this.user, col.id, this.opus.completePath)
        final b = this.divCollectionService.addFragment(this.user, col.id, this.opus.completePath, "1", "1")
        assert this.divCollectionService.getCollection(this.user, col.id).items*.id == [a.id, b.id]

        this.divCollectionService.reorderItems(this.user, col.id, [b.id, a.id])
        assert this.divCollectionService.getCollection(this.user, col.id).items*.id == [b.id, a.id]

        final newName = "renamed_" + System.nanoTime()
        assert this.divCollectionService.renameCollection(this.user, col.id, newName).name == newName
        shouldFail { this.divCollectionService.renameCollection(this.user, this.divCollectionService.createFavoritesIfMissing(this.user).id, "x") }
    }

    @Test
    void savedSearchDefaultsItsNameFromTheQueryAndStaysEditable() {
        final query = "to be or not " + System.nanoTime()
        final hits = [new DivCollectionService.SearchHitToSave(this.opus.completePath, "to be", 0.9f)]
        final saved = this.divCollectionService.saveSearch(this.user, null, query, '{"mode":"lucene"}', hits)
        assert saved.kind == CollectionKind.SEARCH
        assert saved.name == query
        assert saved.searchQuery == query
        assert saved.items.size() == 1
        assert saved.items[0].kind == DivCollectionItem.Kind.SNIPPET
        assert saved.items[0].snippet == "to be"

        final again = this.divCollectionService.saveSearch(this.user, null, query, null, hits)
        assert again.name == query + " (2)"
        assert this.divCollectionService.renameCollection(this.user, saved.id, "Hamlet quotes").name == "Hamlet quotes"
    }
}
