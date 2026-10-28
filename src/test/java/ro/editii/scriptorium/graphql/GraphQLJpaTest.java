package ro.editii.scriptorium.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ro.editii.scriptorium.SecurityConfig;
import ro.editii.scriptorium.model.*;
import ro.editii.scriptorium.media.MediaRef;
import ro.editii.scriptorium.security.AdminUsers;

import java.beans.Introspector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/GraphQL/repository integration, never using a shared datasource. */
@SpringBootTest(classes = GraphQLJpaTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=graphql-test",
        "spring.datasource.url=jdbc:h2:mem:graphql-jpa-test;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=false", "admin.users=admin", "nestjs.internal-api-token=graphql-test-token",
        "spring.graphql.schema.locations=classpath:graphql/**/"})
class GraphQLJpaTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"ro.editii.scriptorium.model", "ro.editii.scriptorium.media", "ro.editii.scriptorium.embedding"})
    @EnableJpaRepositories(basePackages = {"ro.editii.scriptorium.dao", "ro.editii.scriptorium.embedding"})
    @Import({GraphQLJpaConfig.class, GraphQLCatalogConfig.class, SecurityConfig.class, AdminUsers.class,
            ro.editii.scriptorium.security.InternalApiToken.class})
    static class Application {
        @Bean
        UserDetailsService users() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("admin").password(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test")).roles("USER").build(),
                    User.withUsername("reader").password(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test")).roles("USER").build());
        }
    }

    @Value("${local.server.port}") int port;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager em;
    final ObjectMapper json = new ObjectMapper();
    Long authorId, divId, userId;
    String mediaId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var author = Author.builder().strId("graphql-" + System.nanoTime()).lastName("GraphQL").build();
            em.persist(author); authorId = author.getId();
            var file = new TeiFile(); file.setFilename(author.getStrId() + ".xml");
            file.setTitle("GraphQL book"); file.setAuthors(List.of(author));
            em.persist(file);
            var root = new TeiDiv(); root.setTeiFile(file); root.setHead("Root"); root.setNth(7);
            em.persist(root); divId = root.getId();
            var child = new TeiDiv(); child.setTeiFile(file); child.setHead("Child"); child.setParent(root);
            em.persist(child);
            var media = MediaRef.builder().url("https://example.org/" + author.getStrId()).width(123).build();
            em.persist(media); mediaId = media.getUrl();
            var relocation = new Relocation(); relocation.setOldPath(author.getStrId()); relocation.setNewPath("author/opus");
            em.persist(relocation);
            var user = AppUser.builder().username(author.getStrId()).passwordHash("secret").googleSub(author.getStrId()).build();
            em.persist(user); userId = user.getId();
            var collection = DivCollection.builder().owner(user).name("favorites").kind(ro.editii.scriptorium.collection.CollectionKind.FAVORITES).build();
            em.persist(collection);
            var stat = ro.editii.scriptorium.embedding.EmbeddingBatchStat.builder().totalChars(3_000_000_000L).build();
            em.persist(stat);
        });
    }

    @Test
    void everyJpaEntityHasWorkingPagedAndSingleQueries() throws Exception {
        for (var entity : emf.getMetamodel().getEntities()) {
            String name = Introspector.decapitalize(entity.getName());
            String key = entity.getSingularAttributes().stream().filter(jakarta.persistence.metamodel.SingularAttribute::isId)
                    .findFirst().orElseThrow().getName();
            var response = query("{ " + name + "List(size: 1) { " + key + " } }", "admin");
            assertFalse(response.has("errors"), response.toString());
            var list = response.path("data").path(name + "List");
            assertTrue(list.isArray());
            String id = list.isEmpty() ? "missing" : list.get(0).path(key).asText();
            if (entity.getIdType().getJavaType() == Long.class && list.isEmpty()) id = "9223372036854775807";
            var single = query("{ " + name + "(id: " + json.writeValueAsString(id) + ") { " + key + " } }", "admin");
            assertFalse(single.has("errors"), single.toString());
        }
    }

    @Test
    void nestedLazyRelationsAliasesFragmentsAndLongValuesWorkWithoutOpenInView() throws Exception {
        var response = query("query { teiDiv(id: \"" + divId + "\") { a: dbChildren { ... on TeiDiv { head } } "
                + "b: dbChildren { teiFile { authors { ...Name } } } teiFile { title } } "
                + "embeddingBatchStatList { totalChars } } fragment Name on Author { lastName }", null);
        assertFalse(response.has("errors"), response.toString());
        assertEquals("Child", response.at("/data/teiDiv/a/0/head").asText());
        assertEquals("GraphQL", response.at("/data/teiDiv/b/0/teiFile/authors/0/lastName").asText());
        assertEquals(3_000_000_000L, response.at("/data/embeddingBatchStatList/0/totalChars").asLong());
    }

    @Test
    void aliasesFiltersPrimitiveDefaultsStringIdsAndPaginationWork() throws Exception {
        var response = query("{ authorById(id: \"" + authorId + "\") { lastName } "
                + "authors(filter: {id: \"" + authorId + "\"}) { id } "
                + "teiDivList(filter: {id: \"" + divId + "\"}) { nth } "
                + "mediaRef(id: " + json.writeValueAsString(mediaId) + ") { width } }", null);
        assertFalse(response.has("errors"), response.toString());
        assertEquals(7, response.at("/data/teiDivList/0/nth").asInt());
        assertEquals(1, response.at("/data/authors").size());
        assertEquals(123, response.at("/data/mediaRef/width").asInt());
        assertEquals("BAD_REQUEST", query("{ authorList(size: 101) { id } }", null)
                .at("/errors/0/extensions/classification").asText());
        assertTrue(query("{ authorList(page: -1) { id } }", null).has("errors"));
        assertTrue(query("{ authorList(size: 0) { id } }", null).has("errors"));
        assertEquals(0, query("{ authors(page: 999999) { id } }", null).at("/data/authors").size());
    }

    @Test
    void personalEntitiesRequireAdminAndCredentialsAreAbsentFromSchema() throws Exception {
        for (String name : List.of("appUser", "userPreference", "readingProgress", "divCollection", "divCollectionItem")) {
            assertTrue(query("{ " + name + "List { id } }", null).has("errors"));
            assertTrue(query("{ " + name + "List { id } }", "reader").has("errors"));
        }
        var result = query("{ appUser(id: \"" + userId + "\") { username } divCollectionList { kind owner { username } } }", "admin");
        assertFalse(result.has("errors"), result.toString());
        assertEquals("FAVORITES", result.at("/data/divCollectionList/0/kind").asText());
        assertTrue(query("{ appUser(id: \"" + userId + "\") { passwordHash } }", "admin").has("errors"));
        assertTrue(query("{ appUserList(filter: {googleSub: \"x\"}) { id } }", "admin").has("errors"));
    }

    @Test
    void graphqlRequiresAnInternalServerToken() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", "{ authors { strId } }"))));
        var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertNotEquals(200, response.statusCode());
    }

    JsonNode query(String query, String username) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Api-Token", "Bearer graphql-test-token")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", query))));
        if (username != null) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":test").getBytes()));
        var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
