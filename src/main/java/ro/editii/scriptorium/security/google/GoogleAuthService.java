package ro.editii.scriptorium.security.google;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.editii.scriptorium.collection.DivCollectionService;
import ro.editii.scriptorium.dao.AppUserRepository;
import ro.editii.scriptorium.model.AppUser;

@Service
@RequiredArgsConstructor
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

        return this.appUserRepository.findByGoogleSub(claims.sub())
                .map(user -> updateAvatarIfChanged(user, claims))
                .orElseGet(() -> createFromGoogleAccount(claims));
    }

    // Google's profile picture can change over time (or simply wasn't
    // captured yet on accounts created before avatarUrl existed) - refresh
    // it on every sign-in rather than only at account creation.
    private AppUser updateAvatarIfChanged(AppUser user, GoogleClaims claims) {
        if (claims.picture() != null && !claims.picture().equals(user.getAvatarUrl())) {
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

        final AppUser user = AppUser.builder()
                .username(username)
                .googleSub(claims.sub())
                .avatarUrl(claims.picture())
                .passwordHash(null)
                .role(AppUser.Role.USER)
                .build();

        this.appUserRepository.save(user);
        this.divCollectionService.createFavoritesIfMissing(user);
        return user;
    }
}
