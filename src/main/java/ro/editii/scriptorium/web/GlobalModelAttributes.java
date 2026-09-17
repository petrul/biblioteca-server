package ro.editii.scriptorium.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Attributes every Thymeleaf-rendered page needs regardless of which
 * @Controller renders it - the Google One Tap widget (see
 * fragments/google-one-tap.html) needs the configured client id, and needs
 * to know whether someone's already logged in (so it doesn't prompt a
 * signed-in visitor to sign in again).
 */
@ControllerAdvice
public class GlobalModelAttributes {

    @Value("${google.oauth.client-id:}")
    String googleClientId;

    @ModelAttribute("googleClientId")
    public String googleClientId() {
        return this.googleClientId;
    }

    @ModelAttribute("currentUsername")
    public String currentUsername(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken)
            return null;
        return authentication.getName();
    }

    @ModelAttribute("noredirect")
    public String noRedirect(HttpServletRequest request) {
        return request.getParameter("noredirect");
    }
}
