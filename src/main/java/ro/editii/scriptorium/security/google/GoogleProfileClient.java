package ro.editii.scriptorium.security.google;

/** Fetches a verified Google profile from an OAuth access token. */
public interface GoogleProfileClient {
    GoogleProfile fetch(String accessToken);
}
