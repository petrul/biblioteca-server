package ro.editii.scriptorium.rest

import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.transaction.annotation.Transactional
import ro.editii.scriptorium.dao.UserPreferenceRepository
import ro.editii.scriptorium.model.UserPreference

/**
 * GET/PUT /api/users/me/preferences read and write a @Lob - which Postgres
 * keeps as a large object, unreadable in auto-commit mode (the HTTP 500
 * "Unable to access lob stream"). Both must run in a transaction.
 */
class UserPreferencesRestControllerTest {

    @Test
    void bothEndpointsRunInATransactionBecauseThePreferencesAreALargeObject() {
        def get = UserPreferencesRestController.getMethod('get', org.springframework.security.core.Authentication)
        def put = UserPreferencesRestController.getMethod('put', Map, org.springframework.security.core.Authentication)
        assert get.getAnnotation(Transactional)?.readOnly()
        assert put.getAnnotation(Transactional) != null
    }

    @Test
    void storesAndReturnsThePreferencesDocument() {
        UserPreference stored = null
        def repo = [
                findByUsername: { String u -> Optional.ofNullable(stored) },
                save          : { UserPreference p -> stored = p; p },
        ] as UserPreferenceRepository
        def controller = new UserPreferencesRestController(repo)
        def auth = new TestingAuthenticationToken('petru@scriptorium.ro', null)

        assert controller.get(auth) == [:]
        assert controller.put([theme: 'sepia', fontSize: 22], auth) == [theme: 'sepia', fontSize: 22]
        assert stored.username == 'petru@scriptorium.ro'
        assert controller.get(auth) == [theme: 'sepia', fontSize: 22]
    }
}
