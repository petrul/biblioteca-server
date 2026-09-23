package ro.editii.scriptorium.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who admin.users (ADMIN_USERS) names, by username - Google sign-in's
 * username is the account's email, see UserRestController/GoogleAuthController.
 * Deliberately just a static, comma-separated allow-list rather than a
 * per-user DB flag/role: there's exactly one admin today (petru@scriptorium.ro),
 * and a role/permission system would be solving a problem this app doesn't
 * have yet. Revisit if that ever stops being true.
 */
@Component
public class AdminUsers {

    private final Set<String> admins;

    public AdminUsers(@Value("${admin.users:}") String adminUsers) {
        this.admins = Arrays.stream(adminUsers.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(String username) {
        return username != null && this.admins.contains(username.toLowerCase());
    }
}
