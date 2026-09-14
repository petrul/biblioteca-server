package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ro.editii.scriptorium.dao.AppUserRepository;
import ro.editii.scriptorium.model.AppUser;
import ro.editii.scriptorium.security.AppUserRegistrationService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Account creation only - login itself goes through Spring Security's
 * default form-login processing (POST /login, see SecurityConfig), so
 * there's no separate /api/users/login endpoint to build here.
 */
@RestController
@RequestMapping("/api/users")
@CrossOrigin
@RequiredArgsConstructor
@Log4j2
public class UserRestController {

    final AppUserRegistrationService registrationService;
    final AppUserRepository appUserRepository;

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
        log.debug("GET /api/users/me: authentication={}, name={}, authenticated={}",
                authentication == null ? null : authentication.getClass().getSimpleName(),
                authentication == null ? null : authentication.getName(),
                authentication != null && authentication.isAuthenticated());

        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName()))
            return Map.of("authenticated", false);

        final Map<String, Object> result = new HashMap<>();
        result.put("authenticated", true);
        result.put("username", authentication.getName());
        final Optional<AppUser> appUser = this.appUserRepository.findByUsername(authentication.getName());
        log.debug("GET /api/users/me: AppUser lookup for '{}' found={}, avatarUrl={}",
                authentication.getName(), appUser.isPresent(), appUser.map(AppUser::getAvatarUrl).orElse(null));
        appUser.map(AppUser::getAvatarUrl).ifPresent(avatarUrl -> result.put("avatarUrl", avatarUrl));
        return result;
    }
}
