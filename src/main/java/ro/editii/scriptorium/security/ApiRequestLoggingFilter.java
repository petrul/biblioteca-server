package ro.editii.scriptorium.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class ApiRequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ApiRequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        final long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            final String user = authentication != null && authentication.isAuthenticated()
                    && !"anonymousUser".equals(authentication.getPrincipal())
                    ? authentication.getName()
                    : "anonymous";
            final long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            log.info("API {} {} status={} user={} ip={} durationMs={}", request.getMethod(),
                    request.getRequestURI(), response.getStatus(), user, request.getRemoteAddr(), elapsedMs);
        }
    }
}
