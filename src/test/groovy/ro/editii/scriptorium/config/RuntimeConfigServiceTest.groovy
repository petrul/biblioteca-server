package ro.editii.scriptorium.config

import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.assertThrows

class RuntimeConfigServiceTest {
    @Test
    void testValuesAreSeededFromEnvironmentAndWritesAreVisibleThroughSpringEnvironment() {
        def environment = new StandardEnvironment()
        environment.propertySources.addFirst(new MapPropertySource('test', [
                DB_URL       : 'jdbc:derby://test/db;user=u;password=p',
                OLLAMA_HOST  : 'ollama.test',
                TEI_REPOS    : '/corpus/tei|**/*.xml',
                WORK_DIR     : '/tmp/biblioteca'
        ]))

        def service = new RuntimeConfigService(environment)

        assert service.get('DB_URL', false).value() == '********'
        assert service.get('DB_URL', true).value() == 'jdbc:derby://test/db;user=u;password=p'
        assert service.get('OLLAMA_HOST', false).value() == 'ollama.test'
        assert environment.getProperty('ollama.host') == 'ollama.test'
        assert service.get('TEI_REPOS', false).value() == '/corpus/tei|**/*.xml'
        assert service.get('repo.tei.repos', false).restartRequired()

        service.set('OLLAMA_HOST', 'new-ollama.test', false)
        assert service.get('ollama.host', false).value() == 'new-ollama.test'
        assert environment.getProperty('ollama.host') == 'new-ollama.test'

        service.reset('OLLAMA_HOST', false)
        assert service.get('OLLAMA_HOST', false).value() == 'ollama.test'
    }

    @Test
    void testOnlyAllowListedPropertiesCanBeChanged() {
        def service = new RuntimeConfigService(new StandardEnvironment())

        assertThrows(IllegalArgumentException) {
            service.set('spring.application.adminPassword', 'secret', false)
        }
        assertThrows(IllegalArgumentException) {
            service.set('DB_URL', '   ', false)
        }
    }
}
