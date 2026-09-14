package ro.editii.scriptorium.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Date;

/**
 * Named AppUser (not User) to avoid confusion with Spring Security's own
 * org.springframework.security.core.userdetails.User, which this entity is
 * adapted into at login time - see AppUserDetailsService.
 */
@Entity
@Data
@Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppUser implements Serializable {

    public static final int USERNAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(unique = true, nullable = false, length = USERNAME_MAX_LENGTH)
    @Size(max = USERNAME_MAX_LENGTH)
    String username;

    // Null for a Google-only account (see GoogleAuthService) - nothing to
    // check it against, since Google itself is the authenticator for those.
    // BCrypt, never the plain password - see AppUserRegistrationService.
    @JsonIgnore
    String passwordHash;

    // The Google "sub" claim (stable per-account id) - set once, at
    // first Google sign-in (see GoogleAuthService), null for accounts
    // created via plain username/password registration that never linked
    // a Google account.
    @Column(unique = true)
    String googleSub;

    // Google's "picture" claim, refreshed on every Google sign-in (see
    // GoogleAuthService) - null for accounts that never signed in with
    // Google. Just a URL; nothing here fetches or caches the image itself.
    String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    Role role = Role.USER;

    @Builder.Default
    Timestamp createdAt = new Timestamp(new Date().getTime());

    public enum Role { USER, ADMIN }
}
