package ro.editii.scriptorium.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Labels every request's log lines with its {@link Initiator}, before any
 * other filter runs: the label another service sent (see
 * {@link Initiator#received}), else the browser's guest name cookie, else
 * "anonymous". {@link InitiatorAuthenticationFilter} then replaces it with
 * the signed-in user once Spring Security has loaded the session.
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
                .or(() -> guestCookie(request))
                .orElse(Initiator.ANONYMOUS);
    }

    private static Optional<String> guestCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(cookie -> Initiator.GUEST_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(Initiator::isGuest)
                .findFirst();
    }
}
