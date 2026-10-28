package ro.editii.scriptorium.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalApiToken {
    private final String token;

    public InternalApiToken(@Value("${biblioteca.internal-api-token:}") String token) {
        this.token = token;
    }

    public boolean authorizes(String authorization) {
        if (token.isBlank() || authorization == null) return false;
        return MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8));
    }
}
