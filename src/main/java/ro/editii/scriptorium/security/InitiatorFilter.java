package ro.editii.scriptorium.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Labels every request's log lines with its {@link Initiator}, before any
 * other filter runs: the label another service sent (see
 * {@link Initiator#received}), or the guest of the caller's address and
 * browser. {@link InitiatorAuthenticationFilter} then replaces it with the
 * signed-in user once Spring Security has loaded the session.
 *
 * The MDC value is deliberately NOT cleared when the request ends: Tomcat
 * logs a request's uncaught exception ("Servlet.service() ... threw
 * exception") after every filter has returned, and that line must carry
 * its initiator too. The next request on the thread replaces the value
 * here, first thing; threads that serve no requests never get one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InitiatorFilter extends OncePerRequestFilter {

    private final InternalApiToken internalApiToken;

    public InitiatorFilter(InternalApiToken internalApiToken) {
        this.internalApiToken = internalApiToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put(Initiator.MDC_KEY, initiatorOf(request));
        chain.doFilter(request, response);
    }

    String initiatorOf(HttpServletRequest request) {
        final boolean internalCaller = this.internalApiToken.authorizes(request.getHeader("Authorization"))
                || this.internalApiToken.authorizes(request.getHeader("X-Internal-Api-Token"));
        return Initiator.received(request.getHeader(Initiator.HEADER), internalCaller)
                .orElseGet(() -> Initiator.guestLabel(request.getRemoteAddr() + "|"
                        + (request.getHeader("User-Agent") == null ? "" : request.getHeader("User-Agent"))));
    }
}
