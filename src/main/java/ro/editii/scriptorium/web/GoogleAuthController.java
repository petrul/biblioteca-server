package ro.editii.scriptorium.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ro.editii.scriptorium.model.AppUser;
import ro.editii.scriptorium.security.google.GoogleAuthService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Receives what Google's One Tap widget posts after a successful sign-in:
 * a real HTML form POST (not fetch/AJAX - see the data-login_uri config in
 * fragments/google-one-tap.html), so the response here is a redirect, not
 * JSON. Verification/find-or-create is GoogleAuthService's job; this
 * controller's only responsibility is turning the result into a real
 * logged-in Spring Security session.
 */
@Controller
@RequiredArgsConstructor
@Log4j2
public class GoogleAuthController {

    final GoogleAuthService googleAuthService;

    @PostMapping("/api/auth/google")
    public void googleSignIn(@RequestParam("credential") String credential,
                              @RequestParam(value = "redirect", required = false) String redirect,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        log.debug("POST /api/auth/google received, credentialLength={}, redirect={}, sessionId={}",
                credential == null ? 0 : credential.length(), redirect, request.getSession(true).getId());
        try {
            final AppUser user = this.googleAuthService.signIn(credential);
            establishSession(user, request, response);
            final String target = safeRedirectTarget(redirect);
            log.debug("Google sign-in OK for username={}, redirecting to {}", user.getUsername(), target);
            response.sendRedirect(target);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Google sign-in failed ({}): {}", e.getClass().getSimpleName(), e.getMessage(), e);
            response.sendRedirect("/?googleSignInError="
                    + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8));
        }
    }

    private void establishSession(AppUser user, HttpServletRequest request, HttpServletResponse response) {
        final UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password("") // unused - identity was already verified by Google, not a local password
                .roles(user.getRole().name())
                .build();
        // The 3-arg UsernamePasswordAuthenticationToken constructor marks
        // itself as already-authenticated - no AuthenticationManager/password
        // check happens here, deliberately, since Google already vouched
        // for this identity.
        final Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());

        final SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // Not going through Spring Security's normal login filter chain, so
        // the context has to be persisted into the session explicitly here.
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        log.debug("Security session established for username={}, sessionId={}", user.getUsername(), request.getSession().getId());
    }

    private static String safeRedirectTarget(String redirect) {
        // Only ever redirect within this app - an open redirect via a
        // caller-controlled absolute/external URL isn't worth the
        // convenience of an arbitrary "return to this page" parameter.
        if (redirect != null && redirect.startsWith("/") && !redirect.startsWith("//"))
            return redirect;
        return "/";
    }
}
