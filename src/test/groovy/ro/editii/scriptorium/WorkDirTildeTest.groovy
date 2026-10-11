package ro.editii.scriptorium

import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * work.dir is expanded once, at startup, so every ${work.dir}-derived path
 * gets a real directory - zmeu's machine profile sets WORK_DIR=~/.biblioteca.
 */
class WorkDirTildeTest {

    private static StandardEnvironment environment(String workDirEnv) {
        def env = new StandardEnvironment()
        // Isolated from this JVM's own environment (rake exports WORK_DIR, Gradle sets LUCENE_INDEX_DIR).
        env.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        env.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        // The same definitions as application.properties.
        env.propertySources.addLast(new MapPropertySource('applicationProperties', [
                'work.dir'        : '${WORK_DIR:/work}',
                'cache.dir'       : '${work.dir}/cache',
                'lucene.index.dir': '${LUCENE_INDEX_DIR:${work.dir}/lucene-index}',
        ] as Map<String, Object>))
        if (workDirEnv != null) {
            env.propertySources.addFirst(new MapPropertySource('passStore', [WORK_DIR: workDirEnv] as Map<String, Object>))
        }
        new WorkDirTilde().postProcessEnvironment(env, null)
        env
    }

    @Test
    void aTildeWorkDirAndEveryPathDerivedFromItResolveUnderTheHomeDirectory() {
        def home = System.getProperty('user.home')
        def env = environment('~/.biblioteca')
        assert env.getProperty('work.dir') == "${home}/.biblioteca".toString()
        assert env.getProperty('cache.dir') == "${home}/.biblioteca/cache".toString()
        assert env.getProperty('lucene.index.dir') == "${home}/.biblioteca/lucene-index".toString()
    }

    @Test
    void absoluteAndDefaultWorkDirsAreLeftAlone() {
        assert environment('/work').getProperty('work.dir') == '/work'
        assert environment(null).getProperty('work.dir') == '/work'
        assert environment(null).getProperty('cache.dir') == '/work/cache'
    }
}
