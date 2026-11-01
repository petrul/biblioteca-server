package ro.editii.scriptorium.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Once Spring Security knows the request's user, logs as that user (see
 * {@link InitiatorFilter} for the label before this point), and answers with
 * the resolved label in {@link Initiator#HEADER} - biblioteca-reader learns
 * who each session belongs to from it. A sign-in changes the user during
 * the request, so the header is set again afterwards when it still can be.
 */
public class InitiatorAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        label(response);
        try {
            chain.doFilter(request, response);
        } finally {
            if (!response.isCommitted()) label(response);
        }
    }

    private static void label(HttpServletResponse response) {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            MDC.put(Initiator.MDC_KEY, Initiator.user(authentication.getName()));
        }
        final String initiator = MDC.get(Initiator.MDC_KEY);
        if (initiator != null) response.setHeader(Initiator.HEADER, initiator);
    }
}
