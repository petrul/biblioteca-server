package db.migration

import org.flywaydb.core.api.migration.Context
import org.junit.jupiter.api.Test
import ro.editii.scriptorium.model.AppUser

import java.sql.Connection
import java.sql.DriverManager

class V11AvatarUrlMigrationTest {

    // Google photo URLs run to ~1,500 characters (seen in production).
    private static final String LONG_AVATAR_URL = 'https://lh3.googleusercontent.com/a-/' + ('x' * 1500) + '=s96-c'

    @Test
    void widensTheDefaultAvatarColumnSoGooglePhotoUrlsFit() {
        final Connection c = DriverManager.getConnection("jdbc:h2:mem:v11widen;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
        c.createStatement().execute('CREATE TABLE APP_USER (ID BIGINT PRIMARY KEY, USERNAME VARCHAR(100), AVATAR_URL VARCHAR(255))')
        c.createStatement().execute("INSERT INTO APP_USER VALUES (2, 'reader@example.com', 'https://example.com/old.jpg')")

        new V11__Widen_app_user_avatar_url().migrate([getConnection: { c }] as Context)

        assert columnSize(c) == AppUser.AVATAR_URL_MAX_LENGTH
        final def update = c.prepareStatement('UPDATE APP_USER SET AVATAR_URL = ? WHERE ID = 2')
        update.setString(1, LONG_AVATAR_URL)
        assert update.executeUpdate() == 1
        final def rows = c.createStatement().executeQuery('SELECT AVATAR_URL FROM APP_USER WHERE ID = 2')
        assert rows.next() && rows.getString(1) == LONG_AVATAR_URL
    }

    @Test
    void leavesAFreshDatabaseAlone() {
        final Connection c = DriverManager.getConnection("jdbc:h2:mem:v11fresh;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
        new V11__Widen_app_user_avatar_url().migrate([getConnection: { c }] as Context)
        assert !c.metaData.getTables(null, null, 'app_user', null).next()
    }

    private static int columnSize(Connection c) {
        c.metaData.getColumns(null, null, 'app_user', 'avatar_url').withCloseable { it.next() ? it.getInt('COLUMN_SIZE') : -1 }
    }
}
