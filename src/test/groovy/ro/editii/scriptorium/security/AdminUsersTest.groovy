package ro.editii.scriptorium.security

import org.junit.jupiter.api.Test

/**
 * Pure unit coverage for the allow-list parsing/matching itself - no
 * Spring context needed for this part. The other half (that /api/admin/**
 * and GET /api/users/me's isAdmin actually USE this correctly, through
 * the real security filter chain) is AdminUsersAccessTest, which can't be
 * a plain unit test since that's exactly what it needs to exercise.
 */
class AdminUsersTest {

    @Test
    void matchesAConfiguredUsername() {
        assert new AdminUsers("petru@scriptorium.ro").isAdmin("petru@scriptorium.ro")
    }

    @Test
    void doesNotMatchAUsernameNotOnTheList() {
        assert !new AdminUsers("petru@scriptorium.ro").isAdmin("someone-else@example.com")
    }

    @Test
    void matchesCaseInsensitively() {
        assert new AdminUsers("Petru@Scriptorium.ro").isAdmin("petru@scriptorium.ro")
        assert new AdminUsers("petru@scriptorium.ro").isAdmin("PETRU@SCRIPTORIUM.RO")
    }

    @Test
    void supportsMultipleCommaSeparatedAdminsWithWhitespaceTrimmed() {
        final admins = new AdminUsers(" alice@example.com , bob@example.com ")
        assert admins.isAdmin("alice@example.com")
        assert admins.isAdmin("bob@example.com")
        assert !admins.isAdmin("carol@example.com")
    }

    @Test
    void blankConfigMeansNoAdminsAtAll() {
        final admins = new AdminUsers("")
        assert !admins.isAdmin("petru@scriptorium.ro")
        assert !admins.isAdmin("")
    }

    @Test
    void aNullUsernameIsNeverAnAdmin() {
        assert !new AdminUsers("petru@scriptorium.ro").isAdmin(null)
    }
}
