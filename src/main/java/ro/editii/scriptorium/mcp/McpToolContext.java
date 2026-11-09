package ro.editii.scriptorium.mcp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Request-scoped helpers for MCP tools, the same pattern GraphQLCatalogConfig
 * uses to reuse the existing REST handler methods in-process: a tool call runs
 * inside the /mcp HTTP servlet request, so the request attributes Spring MVC
 * publishes (and a builder rooted at the server's own base URL) are available
 * exactly like in a REST request.
 */
final class McpToolContext {

    private McpToolContext() {
    }

    static HttpServletRequest request() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }

    static UriComponentsBuilder baseUri() {
        return ServletUriComponentsBuilder.fromContextPath(request());
    }
}
