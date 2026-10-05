# Reusable Google profile client

The classes in `src/main/java/ro/editii/scriptorium/security/google/`
`GoogleProfile`, `GoogleProfileClient`, and `GoogleUserInfoClient` are a
small, provider-specific adapter that can be copied into another Scriptorium
Spring application (for example `rsq.scriptorium.ro`). They have no
Biblioteca entity or repository dependency:

```java
public record GoogleProfile(String sub, String email, String picture) {}

public interface GoogleProfileClient {
    GoogleProfile fetch(String accessToken);
}
```

`GoogleUserInfoClient` is the Spring `@Service` implementation. It calls the
OpenID Connect `userinfo` endpoint with a bearer access token and returns only
the stable subject, verified email, and optional photo URL. The consuming app
must still verify that the email/subject belongs to its current session before
persisting the photo.

The browser must request the `openid email profile` scopes using Google
Identity Services. The access token should be sent through the app's own
same-origin server, never directly from application code to another service.
