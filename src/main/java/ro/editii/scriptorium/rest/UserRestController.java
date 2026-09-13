package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ro.editii.scriptorium.model.AppUser;
import ro.editii.scriptorium.security.AppUserRegistrationService;

import java.util.Map;

/**
 * Account creation only - login itself goes through Spring Security's
 * default form-login processing (POST /login, see SecurityConfig), so
 * there's no separate /api/users/login endpoint to build here.
 */
@RestController
@RequestMapping("/api/users")
@CrossOrigin
@RequiredArgsConstructor
public class UserRestController {

    final AppUserRegistrationService registrationService;

    @Value
    public static class RegisterRequest {
        String username;
        String password;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest request) {
        try {
            final AppUser user = this.registrationService.register(request.getUsername(), request.getPassword());
            return Map.of("id", user.getId(), "username", user.getUsername());
        } catch (IllegalArgumentException e) {
            RestUtil.throw400(e.getMessage());
            return null; // unreachable - throw400 always throws
        }
    }

    /**
     * Deliberately public (see SecurityConfig) and always 200 - this is how
     * a caller finds out it's anonymous, not something that should 401.
     * Spring Security's anonymous-request default principal name is the
     * literal string "anonymousUser", not null.
     */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName()))
            return Map.of("authenticated", false);
        return Map.of("authenticated", true, "username", authentication.getName());
    }
}
