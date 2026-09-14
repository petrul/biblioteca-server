package ro.editii.scriptorium.security.google;

/** Verified fields out of a Google ID token - see GoogleIdTokenVerifier. */
public record GoogleClaims(String sub, String email, String name, String picture, boolean emailVerified) {
}
