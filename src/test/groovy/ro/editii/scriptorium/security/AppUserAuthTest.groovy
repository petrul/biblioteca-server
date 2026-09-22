package ro.editii.scriptorium.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TextbaseServer
import ro.editii.scriptorium.dao.AppUserRepository
import ro.editii.scriptorium.model.AppUser
import ro.editii.scriptorium.vector.FakeEmbedderTestConfig
import ro.editii.scriptorium.vector.NetworkFreeVectorTestConfig

/**
 * Confirms the migration from the old InMemoryUserDetailsManager (a single
 * hardcoded "petru"/ADMIN account, see git history of SecurityConfig) to
 * real, persisted AppUser rows preserves existing admin access - the
 * highest-risk part of this change - and that registration produces a
 * real, loadable, correctly-hashed account.
 */
@TestPropertySource(properties = [
        "spring.datasource.url=jdbc:h2:mem:appUserAuthTestDb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "milvus.url=http://mini.local:20112",
        "milvus.collection=test_tb_paras_qwen3_embedding_4b_authtest_unused",
        "embeddings.host=mini.local",
        "embeddings.port=11200",
        "ollama.host=zmeu.local",
        "ollama.port=11434",
        "textbase.advertised.url=http://localhost:8080"
])
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = [TextbaseServer.class, TestConfig.class, FakeEmbedderTestConfig.class,
                   NetworkFreeVectorTestConfig.class])
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppUserAuthTest {

    @Autowired AppUserDetailsService appUserDetailsService
    @Autowired AppUserRegistrationService registrationService
    @Autowired AppUserRepository appUserRepository
    @Autowired PasswordEncoder passwordEncoder

    @Test
    void adminAccountIsSeededAndUsableAfterMigration() {
        // ApplicationReadyEvent (AdminUserSeeder) already fired by the time
        // the context is up.
        final admin = this.appUserRepository.findByUsername("petru")
        assert admin.isPresent()
        assert admin.get().role == AppUser.Role.ADMIN

        final UserDetails loaded = this.appUserDetailsService.loadUserByUsername("petru")
        assert loaded != null
        assert loaded.authorities*.authority.contains("ROLE_ADMIN")
        assert this.passwordEncoder.matches("xilofon", loaded.password)
    }

    @Test
    void registrationCreatesARealLoadableAccount() {
        final username = "testuser_" + System.nanoTime()
        final user = this.registrationService.register(username, "correcthorsebattery")

        assert user.id != null
        assert user.role == AppUser.Role.USER

        final UserDetails loaded = this.appUserDetailsService.loadUserByUsername(username)
        assert this.passwordEncoder.matches("correcthorsebattery", loaded.password)
        assert loaded.authorities*.authority.contains("ROLE_USER")
    }

}
