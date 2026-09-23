package ro.editii.scriptorium;

import java.net.URI;

/** Shared parsing for configured network service endpoints. */
public final class UrlUtil {
    private UrlUtil() {}

    public record Endpoint(String scheme, String host, int port) {
        public String authority() { return host + ":" + port; }
    }

    /** Parse a configured endpoint such as {@code http://host:1234}. */
    public static Endpoint endpoint(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Endpoint URL must not be blank");
        }
        final URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid endpoint URL '" + value + "'", e);
        }
        if (uri.getScheme() == null || uri.getHost() == null || uri.getPort() < 0) {
            throw new IllegalArgumentException("Expected URL with scheme, host and port, got '" + value + "'");
        }
        return new Endpoint(uri.getScheme(), uri.getHost(), uri.getPort());
    }
}
