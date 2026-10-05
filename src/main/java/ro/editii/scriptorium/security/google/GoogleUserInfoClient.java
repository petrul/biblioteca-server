package ro.editii.scriptorium.security.google;

import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/** Reusable Google OpenID Connect userinfo adapter for Biblioteca applications. */
@Service
@Log4j2
public class GoogleUserInfoClient implements GoogleProfileClient {
    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public GoogleProfile fetch(String accessToken) {
        if (accessToken == null || accessToken.isBlank())
            throw new IllegalArgumentException("missing Google access token");
        final HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        final Map<?, ?> profile;
        try {
            profile = this.restTemplate.exchange(USERINFO_URL, HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class).getBody();
        } catch (RestClientException e) {
            log.warn("Google userinfo request failed: {}", e.getMessage());
            throw new IllegalArgumentException("invalid Google access token", e);
        }
        if (profile == null || profile.get("sub") == null || profile.get("email") == null)
            throw new IllegalArgumentException("Google userinfo response is incomplete");
        return new GoogleProfile(String.valueOf(profile.get("sub")),
                String.valueOf(profile.get("email")),
                profile.get("picture") == null ? null : String.valueOf(profile.get("picture")));
    }
}
