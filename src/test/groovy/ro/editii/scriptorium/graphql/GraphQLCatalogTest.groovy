package ro.editii.scriptorium.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ro.editii.scriptorium.collection.*;
import ro.editii.scriptorium.dao.*;
import ro.editii.scriptorium.fragment.FragmentResolutionService;
import ro.editii.scriptorium.model.*;
import ro.editii.scriptorium.rest.*;
import ro.editii.scriptorium.service.*;
import ro.editii.scriptorium.tei.TeiRepo;
import ro.editii.scriptorium.toc.Toc;
import ro.editii.scriptorium.vector.*;
import ro.editii.scriptorium.search.lucene.LuceneIndexService;
import ro.editii.scriptorium.search.grep.GrepSearchService;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = GraphQLJpaTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
        "spring.config.name=graphql-test", "spring.datasource.url=jdbc:h2:mem:graphql-catalog-test;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=false", "admin.users=admin", "nestjs.internal-api-token=graphql-test-token"])
@Import(GraphQLNativeMockConfig.class)
class GraphQLCatalogTest {
    @Value('${local.server.port}') int port;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired GraphQLNativeMocks mocks;
    TeiRepo teiRepo;
    @Autowired TeiDivRepository divRepository;
    @PersistenceContext EntityManager em;
    final ObjectMapper json = new ObjectMapper();
    java.util.UUID collectionId;
    String strId, path, chapterPath, collectionName, adminOnlyName, repoName;
    Long divId;

    @BeforeEach
    void seed() {
        mocks.reset();
        teiRepo = mocks.teiRepo;
        String suffix = Long.toString(System.nanoTime());
        strId = "catalog_" + suffix; path = strId + "/work"; chapterPath = path + "/chapter";
        collectionName = "collection_" + suffix; adminOnlyName = "admin_only_" + suffix; repoName = "repo_" + suffix;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var author = Author.builder().strId(strId).lastName("Catalogue " + suffix).firstName("Fixture").build();
            em.persist(author);
            var file = new TeiFile(); file.setFilename(strId + ".xml"); file.setTitle("Fixture work");
            file.setLanguage(Languages.EN); file.setRepoName(repoName); file.setAuthors(List.of(author)); em.persist(file);
            var root = new TeiDiv(); root.setTeiFile(file); root.setHead("Fixture work " + suffix);
            root.setUrlFragment("work"); root.setLang(Languages.EN); root.setXpath("/tei:div[1]"); em.persist(root); divId = root.getId();
            var chapter = new TeiDiv(); chapter.setTeiFile(file); chapter.setHead("Chapter"); chapter.setParent(root);
            chapter.setUrlFragment("chapter"); chapter.setXpath("/tei:div[1]/tei:div[1]"); em.persist(chapter);
            var reader = user("reader"); var admin = user("admin");
            var own = DivCollection.builder().owner(reader).name(collectionName).kind(CollectionKind.FAVORITES).build(); em.persist(own); collectionId = own.getId();
            em.persist(DivCollectionItem.builder().collection(own).kind(DivCollectionItem.Kind.DIV).divPath(path).build());
            em.persist(DivCollection.builder().owner(admin).name(adminOnlyName).build());
            em.persist(ReadingProgress.builder().user(reader).opusPath(path).divPath(chapterPath).touchCount(3).attentionSeconds(123).build());
            em.persist(ReadingProgress.builder().user(admin).opusPath(path).divPath(chapterPath).attentionSeconds(999).build());
            em.createQuery("delete from UserPreference").executeUpdate();
            em.persist(UserPreference.builder().username("reader").preferences("{\"theme\":\"night\"}").build());
            em.persist(UserPreference.builder().username("admin").preferences("{\"private\":\"secret\"}").build());
        });
        mocks.byPath = { String requested ->
            if (requested == chapterPath) return divRepository.findById(divId).orElseThrow().getDbChildrenAsDivs().get(0);
            if (requested != path) return null;
            var div = divRepository.findById(divId).orElseThrow(); div.setTeiRepo(teiRepo); return div;
        };
        mocks.opera = { String requested -> divRepository.findOperaForAuthorStrId(requested) };
        mocks.toc = { Long requested -> new Toc(divRepository.findById(requested).orElseThrow()) };
        mocks.paragraphs = { TeiDiv requested, long offset, int size ->
            if (offset != 0 || size == 0) return List.of();
            var root = divRepository.findById(divId).orElseThrow();
            var para = new TeiElem(); para.setId(9_000_000L); para.setName("p"); para.setTeiFile(root.getTeiFile());
            para.setParent(root); para.setUrlFragment("p1"); para.setXpath("/tei:div[1]/tei:p[1]"); para.setTeiRepo(teiRepo);
            return List.of(para);
        };
        String xml = "<TEI xmlns='http://www.tei-c.org/ns/1.0'><teiHeader><fileDesc><titleStmt/><publicationStmt/><sourceDesc/></fileDesc></teiHeader>" +
                "<text><body><div><head>Fixture work</head><p>Fixture paragraph</p><div><head>Chapter</head></div></div></body></text></TEI>";
        mocks.stream = { String filename -> new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)) };
        mocks.paragraphText = "Fixture paragraph";
    }

    private AppUser user(String username) {
        var existing = em.createQuery("select u from AppUser u where u.username = :name", AppUser.class)
                .setParameter("name", username).getResultStream().findFirst();
        if (existing.isPresent()) return existing.get();
        var user = AppUser.builder().username(username).build(); em.persist(user); return user;
    }

    @Test
    void cataloguePagesAndStableAuthorQueriesMatchRest() throws Exception {
        var response = query("{ authorCatalog(q: \"" + strId + "\") { page size totalItems totalPages items { strId worksCount } } " +
                "workCatalog(q: \"" + strId + "\", lang: \"en\") { page size totalItems items { path head language author { strId } } } " +
                "authorDetails(strId: \"" + strId + "\") { strId displayName opera { path head } } " +
                "authorWorks(strId: \"" + strId + "\") { path } }", null);
        noErrors(response);
        var restAuthors = rest("/api/authors/page?q=" + strId, null);
        var restWorks = rest("/api/works?q=" + strId + "&lang=en", null);
        assertEquals(restAuthors.path("totalItems"), response.at("/data/authorCatalog/totalItems"));
        assertEquals(restWorks.at("/items/0/path"), response.at("/data/workCatalog/items/0/path"));
        assertEquals(path, response.at("/data/authorDetails/opera/0/path").asText());
        assertEquals(1, response.at("/data/authorCatalog/items/0/worksCount").asInt());
        assertEquals(1, response.at("/data/authorWorks").size());
        noErrors(query("{ authorDirectory { strId worksCount } divCatalog(size: 1) { path } }", null));
        assert mocks.coverRequests.any { it instanceof ro.editii.scriptorium.dto.TeiDivDto[] };
    }

    @Test
    void stablePathDetailsTocParagraphContentAndCoverMetadataWork() throws Exception {
        var response = query("{ divByPath(path: \"" + path + "\") { path head } " +
                "divDetails(id: \"" + divId + "\") { path children { path head } } " +
                "divToc(id: \"" + divId + "\", size: 1, page: 1) { path head } " +
                "divParagraphs(divId: \"" + divId + "\", withContent: true) { text text_sha256 } " +
                "coverMetadata(id: \"" + divId + "\") { title publisher author } }", null);
        noErrors(response);
        assertEquals(path, response.at("/data/divByPath/path").asText());
        assertEquals(chapterPath, response.at("/data/divDetails/children/0/path").asText());
        assertEquals(chapterPath, response.at("/data/divToc/0/path").asText());
        assertEquals("Fixture paragraph", response.at("/data/divParagraphs/0/text").asText());
        assertEquals(64, response.at("/data/divParagraphs/0/text_sha256").asText().length());
        assertEquals("Biblioteca", response.at("/data/coverMetadata/publisher").asText());
        var restDiv = rest("/api/divs/" + divId, null);
        assertEquals(restDiv.path("path"), response.at("/data/divDetails/path"));
    }

    @Test
    void systemCollectionsAndEntitySearchReturnTypedResults() throws Exception {
        var response = query("{ worksByAuthor(authorStrId: \"" + strId + "\") { path } " +
                "worksByRepo(repoName: \"" + repoName + "\") { path } worksByLanguage(lang: \"en\") { path } " +
                "repoNames featuredWorks { path } authorMedia { strId imageUrls } " +
                "searchAuthors(q: \"" + strId + "\", limit: 1) { type data { ... on AuthorDto { strId } } } " +
                "searchDivHeads(q: \"" + strId.substring(8) + "\", limit: 1) { data { ... on TeiDivDto { path } } } }", null);
        noErrors(response);
        assertEquals(path, response.at("/data/worksByAuthor/0/path").asText());
        assertEquals(path, response.at("/data/worksByRepo/0/path").asText());
        assertEquals(strId, response.at("/data/searchAuthors/0/data/strId").asText());
        assertEquals(path, response.at("/data/searchDivHeads/0/data/path").asText());
    }

    @Test
    void personalEntityQueriesRequireLoginAndAreScopedToReaderRatherThanAdmin() throws Exception {
        assertFalse(query("{ currentUser { authenticated } }", null).at("/data/currentUser/authenticated").asBoolean());
        assertEquals("reader", query("{ currentUser { username } }", "reader").at("/data/currentUser/username").asText());
        assertTrue(query("{ myCollections { name } }", null).has("errors"));
        assertTrue(query("{ myReadingProgress { opusPath } }", null).has("errors"));
        assertTrue(query("{ myPreferences }", null).has("errors"));
        var response = query("{ myCollection(id: \"" + collectionId + "\") { name kind isFavorites items { divPath divHead } } " +
                "myCollections { name } myWorkReadingProgress(opusPath: \"" + path + "\") { opusPath read attentionSeconds } " +
                "myReadingProgress { opusPath attentionSeconds } myPreferences }", "reader");
        noErrors(response);
        assertTrue(response.at("/data/myCollection/isFavorites").asBoolean());
        assertEquals(path, response.at("/data/myCollection/items/0/divPath").asText());
        assertEquals(123, response.at("/data/myWorkReadingProgress/attentionSeconds").asInt());
        assertEquals("night", response.at("/data/myPreferences/theme").asText());
        assertFalse(response.toString().contains(adminOnlyName));
        assertFalse(response.toString().contains("secret"));
        assertTrue(query("{ myCollection(name: \"" + adminOnlyName + "\") { name } }", "reader").has("errors"));
        assertEquals(rest("/api/reading-progress/one?opusPath=" + path, "reader").path("attentionSeconds"),
                response.at("/data/myWorkReadingProgress/attentionSeconds"));
    }

    @Test
    void schemaRemainsReadOnlyAndBadEntityInputsHaveGraphqlErrors() throws Exception {
        assertTrue(query("{ __schema { mutationType { name } } }", null).at("/data/__schema/mutationType").isNull());
        assertTrue(query("{ searchAuthors(q: \"a\") { type } }", null).has("errors"));
        assertTrue(query("{ searchDivHeads(q: \"abc\", limit: 0) { type } }", null).has("errors"));
        assertEquals("NOT_FOUND", query("{ authorDetails(strId: \"no_such_author\") { strId } }", null)
                .at("/errors/0/extensions/classification").asText());
    }

    void noErrors(JsonNode result) { assertFalse(result.has("errors"), result.toString()); }
    JsonNode query(String query, String username) throws Exception {
        return send("/graphql", json.writeValueAsString(Map.of("query", query)), username);
    }
    JsonNode rest(String path, String username) throws Exception { return send(path, null, username); }
    JsonNode send(String path, String body, String username) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (path == '/graphql') request.header('X-Internal-Api-Token', 'Bearer graphql-test-token');
        if (body == null) request.GET(); else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (username != null) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":test").getBytes()));
        var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
