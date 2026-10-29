package ro.editii.scriptorium.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;
import ro.editii.scriptorium.fragment.FragmentSelector;
import ro.editii.scriptorium.fragment.UnifiedFragmentService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Public fragment representations. Existing raw exports and API/vector source retrieval bypass this. */
@Configuration
@RequiredArgsConstructor
public class UnifiedFragmentWebRoutes implements WebMvcConfigurer {
    final UnifiedFragmentService fragments;
    final ObjectMapper objectMapper = new ObjectMapper();

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
                String path = URLDecoder.decode(req.getRequestURI(), StandardCharsets.UTF_8);
                if (!req.getMethod().equals("GET") || !path.matches("/(?:quote/)?[a-z0-9_]+/[^/]+(?:/.*)?") ||
                        path.startsWith("/api/") || path.contains("/_binary/") ||
                        path.matches(".*\\.(html|txt|xml|json)$")) return true;
                boolean legacyQuote = path.startsWith("/quote/");
                if (legacyQuote) path = path.substring(6);
                String last = path.substring(path.lastIndexOf('/') + 1);
                boolean range = req.getParameter("start") != null || req.getParameter("end") != null;
                boolean selector = last.matches("_[0-9].*");
                if (!range && !selector) return true;
                try {
                    for (String key : new String[]{"start", "end", "rev", "display"})
                        if (req.getParameterValues(key) != null && req.getParameterValues(key).length > 1)
                            throw new IllegalArgumentException("Repeated fragment parameter");
                    boolean rawLegacy = selector && last.matches("_[0-9]+") && !range;
                    String source = selector ? path.substring(0, path.lastIndexOf('/')) : path;
                    String expression = range ? FragmentSelector.fromLegacy(req.getParameter("start"), req.getParameter("end")).toString() : last;
                    var fragment = rawLegacy ? fragments.resolve(path, null, req.getParameter("rev")) : fragments.resolve(source, expression, req.getParameter("rev"));
                    if (fragment.kind().equals("page")) {
                        if (path.equals(fragment.canonicalPath())) return true;
                        res.setStatus(302); res.setHeader("Location", fragment.canonicalPath()); return false;
                    }
                    String accept = req.getHeader("Accept");
                    res.setCharacterEncoding("UTF-8");
                    res.setHeader("Cache-Control", "private, no-cache"); res.setHeader("Vary", "Accept");
                    if (range || legacyQuote || !fragment.canonicalPath().split("\\?")[0].equals(path) || req.getParameter("rev") == null) { res.setStatus(302); res.setHeader("Location", fragment.canonicalPath()); return false; }
                    if (accept != null && accept.contains("application/json")) {
                        res.setContentType("application/json"); objectMapper.writeValue(res.getWriter(), fragment);
                    } else if (accept != null && accept.contains("text/plain")) {
                        res.setContentType("text/plain"); res.getWriter().write(fragment.text());
                    } else if (accept != null && accept.contains("text/markdown")) {
                        res.setContentType("text/markdown"); res.getWriter().write(fragment.text() + "\n\n— " + fragment.authorName() + ", " + fragment.workTitle() + "\n" + fragment.canonicalPath());
                    } else if (accept == null || accept.contains("*/*") || accept.contains("text/html")) {
                        String html = "context".equals(req.getParameter("display")) ? fragments.contextHtml(fragment) : fragment.html();
                        res.setContentType("text/html");
                        res.getWriter().write("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>" +
                                escape(fragment.workTitle() + " — " + fragment.authorName()) + "</title><link rel=\"canonical\" href=\"" + escape(fragment.canonicalPath()) +
                                "\"><link rel=\"stylesheet\" href=\"/css/quote.css\"><style>table{border-collapse:collapse}td{border:1px solid #ccb;padding:.4em}.l{margin:0}mark{background:#f8d981}</style></head><body><main class=\"quote-card\"><blockquote class=\"quote-text\">" + html +
                                "</blockquote><footer class=\"quote-citation\">" + escape(fragment.authorName() + " — " + fragment.workTitle() + " · " + fragment.chapterTitle()) +
                                "<p><a href=\"" + escape(fragment.contextPath()) + "\">Read in full chapter context</a></p></footer></main></body></html>");
                    } else { res.setStatus(406); res.getWriter().write("Unsupported fragment representation"); }
                    return false;
                } catch (ResponseStatusException e) { res.setStatus(e.getStatusCode().value()); res.getWriter().write(e.getReason()); return false; }
                catch (org.springframework.data.rest.webmvc.ResourceNotFoundException e) { res.setStatus(404); res.getWriter().write("Fragment source not found"); return false; }
                catch (IllegalArgumentException e) { res.setStatus(400); res.getWriter().write(e.getMessage()); return false; }
            }
        });
    }

    private static String escape(String text) { return HtmlUtils.htmlEscape(text == null ? "" : text); }
}
