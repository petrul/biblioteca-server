package ro.editii.scriptorium.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.editii.scriptorium.dto.HitDto;
import ro.editii.scriptorium.rest.SearchRestController;

import java.util.List;

/**
 * MCP access to the six corpus search modes of {@code /api/search}. Each tool
 * calls the same SearchRestController handler the REST endpoint uses, so
 * limits, scopes and response shapes stay identical to the public REST API -
 * read-only by construction, and no user information is involved.
 */
@Service
public class McpSearchTools {

    private final ObjectProvider<SearchRestController> search;

    public McpSearchTools(ObjectProvider<SearchRestController> search) {
        this.search = search;
    }

    /** Bean validation (@Size) applies to web binding only; tools validate here. */
    private static String queryText(String q) {
        if (q == null || q.trim().length() < 3)
            throw new IllegalArgumentException("q must contain at least 3 characters");
        return q.trim();
    }

    private static int queryLimit(Integer limit) {
        return limit == null ? 10 : limit;
    }

    @Tool(name = "search_authors", description =
            "Search author names by prefix or substring. Returns hits with the author's stable id " +
            "(strId) the author catalog tools use.")
    public List<HitDto> searchAuthors(
            @ToolParam(description = "Text to find in author names; at least 3 characters") String q,
            @ToolParam(required = false, description = "Maximum number of hits; default 10") Integer limit) {
        return this.search.getObject().searchAuthors(queryText(q), queryLimit(limit),
                McpToolContext.baseUri(), McpToolContext.request());
    }

    @Tool(name = "search_div_heads", description =
            "Search chapter/section titles (heads) across the corpus, optionally narrowed to one " +
            "author or work through scope paths like 'shakespeare' or 'shakespeare/hamlet'.")
    public List<HitDto> searchDivHeads(
            @ToolParam(description = "Text to find in division heads; at least 3 characters") String q,
            @ToolParam(required = false, description = "Maximum number of hits; default 10") Integer limit,
            @ToolParam(required = false, description = "Optional corpus paths narrowing the search") List<String> scope) {
        return this.search.getObject().searchDivHeads(queryText(q), queryLimit(limit), scope,
                McpToolContext.baseUri());
    }

    @Tool(name = "search_lucene", description =
            "Per-language full-text search (Lucene, with language-appropriate stemming) inside the " +
            "corpus. Returns hits with the div path and a text excerpt.")
    public List<HitDto> searchLucene(
            @ToolParam(description = "Text to find in the corpus; at least 3 characters") String q,
            @ToolParam(required = false, description = "Maximum number of hits; default 10") Integer limit,
            @ToolParam(required = false, description = "Optional corpus paths narrowing the search") List<String> scope) {
        return this.search.getObject().searchLucene(queryText(q), queryLimit(limit), scope,
                McpToolContext.baseUri());
    }

    @Tool(name = "search_grep", description =
            "Literal scan of everything in the corpus (no stemming, no tokenizer): the mode that " +
            "always finds an exact string, at the cost of a slower scan.")
    public List<HitDto> searchGrep(
            @ToolParam(description = "Literal text to find in the corpus; at least 3 characters") String q,
            @ToolParam(required = false, description = "Maximum number of hits; default 10") Integer limit,
            @ToolParam(required = false, description = "Optional corpus paths narrowing the search") List<String> scope) {
        return this.search.getObject().searchGrep(queryText(q), queryLimit(limit), scope);
    }

    @Tool(name = "search_vector", description =
            "Semantic similarity search over the corpus paragraphs (vector store + embeddings). " +
            "Requires the vector stack to be available; returns div hits without excerpts.")
    public List<HitDto> searchVector(
            @ToolParam(description = "Meaning to look for; at least 3 characters") String q,
            @ToolParam(required = false, description = "Maximum number of hits; default 10") Integer limit,
            @ToolParam(required = false, description = "Optional corpus paths narrowing the search") List<String> scope) {
        return this.search.getObject().searchVector(queryText(q), queryLimit(limit), scope,
                McpToolContext.baseUri());
    }

    @Tool(name = "search_ann", description =
            "Approximate nearest-neighbour semantic search: returns the divisions most similar to " +
            "an existing one, identified either by its corpus path or its numeric div id. " +
            "Needs exactly one of path/divid.")
    public Object searchAnn(
            @ToolParam(required = false, description = "Corpus path (author/opus/chapter/…) of the reference div") String path,
            @ToolParam(required = false, description = "Numeric div id of the reference div (from div_by_path)") Long divid) {
        if (path == null && divid == null)
            throw new IllegalArgumentException("either 'path' or 'divid' must be provided");
        return this.search.getObject().ann(path, divid, McpToolContext.baseUri(), McpToolContext.request());
    }
}
