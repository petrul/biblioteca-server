package ro.editii.scriptorium.security.google;

/** The small, provider-neutral part of Google's OpenID userinfo response used by Biblioteca. */
public record GoogleProfile(String sub, String email, String picture) {
}
