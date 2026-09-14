package ro.editii.scriptorium.security.google;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.editii.scriptorium.collection.DivCollectionService;
import ro.editii.scriptorium.dao.AppUserRepository;
import ro.editii.scriptorium.model.AppUser;

@Service
@RequiredArgsConstructor
@Log4j2
public class GoogleAuthService {

    final AppUserRepository appUserRepository;
    final DivCollectionService divCollectionService;
    final GoogleIdTokenVerifier googleIdTokenVerifier;

    /**
     * @return the AppUser this Google credential belongs to - an existing
     * one if this Google account has signed in here before (matched by its
     * stable "sub" claim), otherwise a brand new one, created on the spot.
     */
    @Transactional
    public AppUser signIn(String idToken) {
        final GoogleClaims claims = this.googleIdTokenVerifier.verify(idToken);

        final boolean existing = this.appUserRepository.findByGoogleSub(claims.sub()).isPresent();
        log.debug("GoogleAuthService.signIn: sub={}, existingAccount={}", claims.sub(), existing);

        return this.appUserRepository.findByGoogleSub(claims.sub())
                .map(user -> updateAvatarIfChanged(user, claims))
                .orElseGet(() -> createFromGoogleAccount(claims));
    }

    // Google's profile picture can change over time (or simply wasn't
    // captured yet on accounts created before avatarUrl existed) - refresh
    // it on every sign-in rather than only at account creation.
    private AppUser updateAvatarIfChanged(AppUser user, GoogleClaims claims) {
        if (claims.picture() != null && !claims.picture().equals(user.getAvatarUrl())) {
            log.debug("GoogleAuthService: refreshing avatarUrl for username={} (was={}, now={})",
                    user.getUsername(), user.getAvatarUrl(), claims.picture());
            user.setAvatarUrl(claims.picture());
            this.appUserRepository.save(user);
        }
        return user;
    }

    private AppUser createFromGoogleAccount(GoogleClaims claims) {
        String username = claims.email() != null && !claims.email().isBlank()
                ? claims.email()
                : "google_" + claims.sub();

        // Extremely unlikely (a password-registered account happens to
        // already have this exact username/email) but cheap to guard.
        if (this.appUserRepository.existsByUsername(username))
            username = username + "_" + claims.sub().substring(0, Math.min(6, claims.sub().length()));

        log.debug("GoogleAuthService: creating new AppUser username={}, sub={}", username, claims.sub());

        final AppUser user = AppUser.builder()
                .username(username)
                .googleSub(claims.sub())
                .avatarUrl(claims.picture())
                .passwordHash(null)
                .role(AppUser.Role.USER)
                .build();

        this.appUserRepository.save(user);
        this.divCollectionService.createFavoritesIfMissing(user);
        log.debug("GoogleAuthService: created AppUser id={}, username={}", user.getId(), user.getUsername());
        return user;
    }
}
