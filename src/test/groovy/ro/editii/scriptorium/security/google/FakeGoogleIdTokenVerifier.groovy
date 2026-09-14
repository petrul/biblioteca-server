package ro.editii.scriptorium.security.google

/**
 * Test double for GoogleIdTokenVerifier - treats the "token" as literally
 * "sub|email|name|picture", so tests can construct arbitrary Google
 * identities without needing a real Google credential or network call. See
 * FakeGoogleAuthTestConfig for how this replaces the real
 * GoogleTokenInfoVerifier bean in tests.
 */
class FakeGoogleIdTokenVerifier implements GoogleIdTokenVerifier {

    static final String INVALID_TOKEN = "__invalid__"

    @Override
    GoogleClaims verify(String idToken) {
        if (idToken == null || idToken == INVALID_TOKEN)
            throw new IllegalArgumentException("invalid Google credential")

        final parts = idToken.split('\\|', 4)
        return new GoogleClaims(
                parts[0],
                parts.length > 1 ? parts[1] : null,
                parts.length > 2 ? parts[2] : null,
                parts.length > 3 ? parts[3] : null,
                true)
    }

    static String fakeToken(String sub, String email = null, String name = null, String picture = null) {
        return [sub, email, name, picture].join('|')
    }
}
