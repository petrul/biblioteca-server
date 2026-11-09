package ro.editii.scriptorium.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.dto.AuthorDto;
import ro.editii.scriptorium.dto.AuthorMediaDto;
import ro.editii.scriptorium.dto.CatalogPageDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.dto.TeiElemDto;
import ro.editii.scriptorium.model.Languages;
import ro.editii.scriptorium.rest.AuthorRestController;
import ro.editii.scriptorium.rest.DivCollectionRestController;
import ro.editii.scriptorium.rest.DivRestController;
import ro.editii.scriptorium.rest.InfoRestController;
import ro.editii.scriptorium.rest.RelocationRestController;
import ro.editii.scriptorium.rest.StatusRestController;
import ro.editii.scriptorium.rest.WorkCatalogRestController;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.springframework.util.ReflectionUtils;

/**
 * MCP access to the read-only catalog and structure surface of the REST API:
 * authors, works, divs, system collections, relocations and status. Each tool
 * calls the same controller handler its REST endpoint uses, so paging and
 * response shapes are identical to the public API. Deliberately absent: every
 * user-scoped endpoint (/api/users/*, reading progress, personal collections,
 * admin) - this tool set exposes the book material only.
 */
@Service
public class McpCatalogTools {

    private final ObjectProvider<AuthorRestController> authors;
    private final ObjectProvider<WorkCatalogRestController> works;
    private final ObjectProvider<DivRestController> divs;
    private final ObjectProvider<DivCollectionRestController> collections;
    private final ObjectProvider<RelocationRestController> relocations;
    private final ObjectProvider<StatusRestController> status;
    private final ObjectProvider<InfoRestController> info;

    public McpCatalogTools(ObjectProvider<AuthorRestController> authors,
            ObjectProvider<WorkCatalogRestController> works,
            ObjectProvider<DivRestController> divs,
            ObjectProvider<DivCollectionRestController> collections,
            ObjectProvider<RelocationRestController> relocations,
            ObjectProvider<StatusRestController> status,
            ObjectProvider<InfoRestController> info) {
        this.authors = authors;
        this.works = works;
        this.divs = divs;
        this.collections = collections;
        this.relocations = relocations;
        this.status = status;
        this.info = info;
    }

    @Tool(name = "server_info", description =
            "Build identity of this biblioteca-server (app, version, git commit). Useful to check " +
            "what deployment an MCP session is talking to.")
    public Map<String, String> serverInfo() {
        return this.info.getObject().info();
    }

    @Tool(name = "authors_directory", description =
            "All authors with their stable ids and work counts. Prefer authors_page for large corpora.")
    public List<AuthorDto> authorsDirectory() {
        return this.authors.getObject().getAuthors(McpToolContext.baseUri(), McpToolContext.request());
    }

    @Tool(name = "authors_page", description =
            "One page of the author catalog, optionally filtered by name substring, like " +
            "GET /api/authors/page.")
    public CatalogPageDto<AuthorDto> authorsPage(
            @ToolParam(required = false, description = "Optional name substring filter") String q,
            @ToolParam(required = false, description = "1-based page number; default 1") Integer page,
            @ToolParam(required = false, description = "Page size; default 12") Integer size) {
        return this.authors.getObject().getAuthorPage(orDefault(page, 1), orDefault(size, 12), q,
                McpToolContext.baseUri(), McpToolContext.request());
    }

    @Tool(name = "author_details", description =
            "One author (opus list included) by stable id, e.g. 'shakespeare'.")
    public AuthorDto authorDetails(
            @ToolParam(description = "The author's stable id (strId)") String strId) {
        return this.authors.getObject().getAuthor(strId, McpToolContext.baseUri(), McpToolContext.request());
    }

    @Tool(name = "author_works", description =
            "The opuses (root works) of one author, by stable author id.")
    public TeiDivDto[] authorWorks(
            @ToolParam(description = "The author's stable id (strId)") String strId) {
        return this.authors.getObject().getOpera(strId, McpToolContext.baseUri());
    }

    @Tool(name = "author_media", description =
            "Enrichment-collected image URLs per author (portraits, busts). Only corpus material, " +
            "no user data.")
    public List<AuthorMediaDto> authorMedia() {
        return this.authors.getObject().getAllAuthorMedia();
    }

    @Tool(name = "works_catalog", description =
            "One page of the online works catalogue (root works only, no chapters), optionally " +
            "filtered by title substring and language, like GET /api/works.")
    public CatalogPageDto<TeiDivDto> worksCatalog(
            @ToolParam(required = false, description = "Optional title substring filter") String q,
            @ToolParam(required = false, description = "Optional language filter, e.g. EN, FR, RO") String lang,
            @ToolParam(required = false, description = "1-based page number; default 1") Integer page,
            @ToolParam(required = false, description = "Page size; default 12") Integer size) {
        return this.works.getObject().page(orDefault(page, 1), orDefault(size, 12), q, lang,
                McpToolContext.baseUri());
    }

    @Tool(name = "divs_page", description =
            "All divisions (works and chapters) one database page at a time; the raw GET /api/divs/ listing.")
    public List<TeiDivDto> divsPage(
            @ToolParam(required = false, description = "0-based page number; default 0") Integer page,
            @ToolParam(required = false, description = "Page size; default 20") Integer size) {
        return this.divs.getObject().getAllTeiDivs(orDefault(page, 0), orDefault(size, 20), McpToolContext.baseUri());
    }

    @Tool(name = "div_by_path", description =
            "The division or paragraph addressed by a stable corpus path, e.g. " +
            "'shakespeare/hamlet/act_iv/another_room_in_the_castle'. Plays book material only.")
    public TeiElemDto divByPath(
            @ToolParam(description = "Stable path of the element, author first") String path) {
        // The REST handler has package visibility; invoke its Spring proxy like
        // GraphQLCatalogConfig does, keeping transactions and behavior identical.
        final Method handler = ReflectionUtils.findMethod(DivRestController.class, "get_by_path",
                String.class, UriComponentsBuilder.class);
        ReflectionUtils.makeAccessible(handler);
        return (TeiElemDto) ReflectionUtils.invokeMethod(handler, this.divs.getObject(),
                path, McpToolContext.baseUri());
    }

    @Tool(name = "div_details", description =
            "One division by numeric id, with children, like GET /api/divs/{id}.")
    public TeiDivDto divDetails(
            @ToolParam(description = "Numeric div id (from div_by_path, search hits or catalogs)") long id) {
        return this.divs.getObject().get_id(id, McpToolContext.baseUri(), McpToolContext.request());
    }

    @Tool(name = "div_toc", description =
            "Table of contents of one div (its division children in book order), paged, like " +
            "GET /api/divs/{id}/toc.")
    public TeiDivDto[] divToc(
            @ToolParam(description = "Numeric div id") long id,
            @ToolParam(required = false, description = "0-based page number; default 0") Integer page,
            @ToolParam(required = false, description = "Page size; default 20") Integer size) {
        return this.divs.getObject().get_id_toc(id, orDefault(page, 0), orDefault(size, 20),
                McpToolContext.baseUri(), McpToolContext.request());
    }

    @Tool(name = "div_paragraphs", description =
            "The paragraphs of one div, paged, optionally with their text content, like " +
            "GET /api/divs/{divId}/paras.")
    public TeiElemDto[] divParagraphs(
            @ToolParam(description = "Numeric div id") long divId,
            @ToolParam(required = false, description = "0-based page number; default 0") Integer page,
            @ToolParam(required = false, description = "Page size; default 20") Integer size,
            @ToolParam(required = false, description = "Also return each paragraph's text") Boolean withContent) {
        return this.divs.getObject().get_id_paras(divId, orDefault(page, 0), orDefault(size, 20),
                Boolean.TRUE.equals(withContent) ? "true" : null,
                McpToolContext.baseUri(), McpToolContext.request());
    }

    @Tool(name = "cover_metadata", description =
            "One work's bibliographic metadata in the cover renderer's BookMetadata shape (title, " +
            "author, language, genre and what the TEI header carries).")
    public Object coverMetadata(
            @ToolParam(description = "Numeric div id, or the work's path with '/' replaced by '-' " +
                    "(alecsandri/poezii -> alecsandri-poezii)") String id,
            @ToolParam(required = false, description = "Also include the raw TEI XML source; default false") Boolean includeRawTei) {
        return this.divs.getObject().getCoverMetadata(id, Boolean.TRUE.equals(includeRawTei));
    }

    @Tool(name = "works_by_language", description =
            "The system collection of all works in one language.")
    public List<TeiDivDto> worksByLanguage(
            @ToolParam(description = "Language code, e.g. EN, FR, RO") String lang) {
        return this.collections.getObject().byLanguage(lang, McpToolContext.baseUri());
    }

    @Tool(name = "works_by_author", description =
            "The system collection of one author's works.")
    public List<TeiDivDto> worksByAuthor(
            @ToolParam(description = "The author's stable id (strId)") String authorStrId) {
        return this.collections.getObject().byAuthor(authorStrId, McpToolContext.baseUri());
    }

    @Tool(name = "works_by_repo", description =
            "The system collection of all works imported from one TEI repository.")
    public List<TeiDivDto> worksByRepo(
            @ToolParam(description = "TEI repository name (see repo_names)") String repoName) {
        return this.collections.getObject().byRepo(repoName, McpToolContext.baseUri());
    }

    @Tool(name = "repo_names", description =
            "The TEI repositories the corpus was imported from.")
    public List<String> repoNames() {
        return this.collections.getObject().repoNames();
    }

    @Tool(name = "featured_works", description =
            "The featured works shelf (a system collection, curated by corpus admins - no user data).")
    public List<TeiDivDto> featuredWorks() {
        return this.collections.getObject().featured(McpToolContext.baseUri());
    }

    @Tool(name = "discover_works", description =
            "A random sample of works, the Discover shelf's server-side source. Size capped at 50.")
    public List<TeiDivDto> discoverWorks(
            @ToolParam(required = false, description = "How many works to sample; default 20, max 50") Integer size) {
        // 20 mirrors DivCollectionRestController's own default (its constant
        // is package-private; the REST handler validates the 1..50 range).
        return this.collections.getObject().discover(size == null ? 20 : size, McpToolContext.baseUri());
    }

    @Tool(name = "relocation", description =
            "Where a corpus path moved after a TEI reimport (relocation chains followed); " +
            "404-style tool error when the path was never relocated.")
    public RelocationRestController.RelocationDto relocation(
            @ToolParam(description = "The old corpus path, e.g. 'shakespeare/hamlet/act_iv/old_name'") String path) {
        return this.relocations.getObject().get(path);
    }

    @Tool(name = "lucene_status", description =
            "Operational status of the full-text (Lucene) index - is a rebuild running, when did it finish.")
    public Object luceneStatus() {
        return this.status.getObject().luceneStatus();
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
