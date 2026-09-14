package ro.editii.scriptorium.security.google;

import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies a Google ID token server-side via Google's own tokeninfo
 * endpoint, rather than fetching/caching Google's JWKS and verifying the
 * JWT signature locally - no new JWT/JOSE library dependency, and Google
 * handles key rotation entirely on its side. Google documents this as a
 * supported verification method for exactly this (low/medium volume,
 * server-to-server) use; it's rate-limited at higher traffic than a small
 * app's login volume would ever hit.
 */
@Service
@Log4j2
public class GoogleTokenInfoVerifier implements GoogleIdTokenVerifier {

    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token={token}";

    // Deliberately no default: an unset client id must disable the feature
    // (see GoogleAuthService), not silently accept tokens meant for some
    // other Google app - see feedback_no_silent_config_defaults precedent
    // for this codebase (TRAFILATURA_BASE_URL-style external config).
    @Value("${google.oauth.client-id:}")
    String expectedClientId;

    private final RestTemplate restTemplate = new RestTemplate();

    public boolean isConfigured() {
        return this.expectedClientId != null && !this.expectedClientId.isBlank();
    }

    @Data
    static class TokenInfoResponse {
        String sub;
        String email;
        String name;
        String picture;
        String email_verified;
        String aud;
        String iss;
    }

    @Override
    public GoogleClaims verify(String idToken) {
        if (!isConfigured())
            throw new IllegalStateException("Google sign-in is not configured (google.oauth.client-id is unset)");
        if (idToken == null || idToken.isBlank())
            throw new IllegalArgumentException("missing Google credential");

        final TokenInfoResponse info;
        try {
            info = this.restTemplate.getForObject(TOKENINFO_URL, TokenInfoResponse.class, idToken);
        } catch (RestClientException e) {
            log.warn("Google tokeninfo verification failed: {}", e.getMessage());
            throw new IllegalArgumentException("invalid Google credential", e);
        }
        if (info == null || info.getSub() == null)
            throw new IllegalArgumentException("invalid Google credential (empty tokeninfo response)");

        if (!this.expectedClientId.equals(info.getAud()))
            throw new IllegalArgumentException("Google credential audience does not match this app's client id");
        if (!"accounts.google.com".equals(info.getIss()) && !"https://accounts.google.com".equals(info.getIss()))
            throw new IllegalArgumentException("unexpected Google credential issuer: " + info.getIss());

        return new GoogleClaims(info.getSub(), info.getEmail(), info.getName(), info.getPicture(), "true".equals(info.getEmail_verified()));
    }
}
