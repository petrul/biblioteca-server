package ro.editii.scriptorium.search;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Canonical author/opus (or descendant) prefixes accepted by search scope. */
public final class SearchScope {
    private static final int MAX_SCOPES = 32;
    private static final int MAX_PATH_LENGTH = 500;

    private SearchScope() {}

    public static List<String> normalize(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        final Set<String> unique = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank())
                throw new IllegalArgumentException("scope paths must not be blank");
            String path = value.trim().replaceAll("^/+|/+$", "");
            if (path.length() > MAX_PATH_LENGTH || path.startsWith(".") || path.contains("//"))
                throw new IllegalArgumentException("invalid search scope path: " + value);
            String[] parts = path.split("/");
            if (parts.length < 2 || path.contains("..") || path.indexOf('?') >= 0 || path.indexOf('#') >= 0)
                throw new IllegalArgumentException("search scope must be an author/opus path: " + value);
            unique.add(path);
            if (unique.size() > MAX_SCOPES)
                throw new IllegalArgumentException("at most " + MAX_SCOPES + " search scopes are allowed");
        }
        return List.copyOf(unique);
    }

    public static boolean matches(String path, Collection<String> scopes) {
        if (path == null || scopes == null || scopes.isEmpty()) return scopes == null || scopes.isEmpty();
        String normalized = path.trim().replaceFirst("^/+", "").replaceFirst("\\.(txt|xml|json|xhtml)$", "");
        return scopes.stream().anyMatch(scope -> normalized.equals(scope) || normalized.startsWith(scope + "/"));
    }
}
