package ro.editii.scriptorium

import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

import static org.junit.jupiter.api.Assertions.assertThrows

class DbEngineDefaultsTest {

    private static MockEnvironment withUrl(String url) {
        new MockEnvironment().withProperty('spring.datasource.url', url)
    }

    @Test
    void a_postgresql_url_gets_its_driver_and_dialect() {
        final env = withUrl('jdbc:postgresql://db.example/biblioteca?user=u&password=p')
        new DbEngineDefaults().postProcessEnvironment(env, null)
        assert env.getProperty('spring.datasource.driver-class-name') == 'org.postgresql.Driver'
        assert env.getProperty('spring.jpa.database-platform') == 'org.hibernate.dialect.PostgreSQLDialect'
    }

    @Test
    void a_derby_url_stops_the_server_with_the_reason__derby_is_retired() {
        final error = assertThrows(IllegalStateException) {
            new DbEngineDefaults().postProcessEnvironment(withUrl('jdbc:derby://db.example/biblioteca;user=u;password=p'), null)
        }
        assert error.message.contains('Derby is no longer supported')
        assert !error.message.contains('password=p') // the URL itself is never echoed
    }

    @Test
    void any_other_url_is_left_to_spring_boot() {
        final env = withUrl('jdbc:h2:mem:test')
        new DbEngineDefaults().postProcessEnvironment(env, null)
        assert env.getProperty('spring.datasource.driver-class-name') == null
    }
}
