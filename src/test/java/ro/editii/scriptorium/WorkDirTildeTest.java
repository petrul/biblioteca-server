package ro.editii.scriptorium;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * work.dir is expanded once, at startup, so every ${work.dir}-derived path
 * gets a real directory - zmeu's machine profile sets WORK_DIR=~/.biblioteca.
 */
class WorkDirTildeTest {

    private static StandardEnvironment environment(String workDirEnv) {
        StandardEnvironment env = new StandardEnvironment();
        // Isolated from this JVM's own environment (rake exports WORK_DIR, Gradle sets LUCENE_INDEX_DIR).
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        // The same definitions as application.properties.
        env.getPropertySources().addLast(new MapPropertySource("applicationProperties", Map.of(
                "work.dir", "${WORK_DIR:/work}",
                "cache.dir", "${work.dir}/cache",
                "lucene.index.dir", "${LUCENE_INDEX_DIR:${work.dir}/lucene-index}")));
        if (workDirEnv != null) {
            env.getPropertySources().addFirst(new MapPropertySource("passStore", Map.of("WORK_DIR", workDirEnv)));
        }
        new WorkDirTilde().postProcessEnvironment(env, null);
        return env;
    }

    @Test
    void aTildeWorkDirAndEveryPathDerivedFromItResolveUnderTheHomeDirectory() {
        String home = System.getProperty("user.home");
        StandardEnvironment env = environment("~/.biblioteca");
        assertEquals(home + "/.biblioteca", env.getProperty("work.dir"));
        assertEquals(home + "/.biblioteca/cache", env.getProperty("cache.dir"));
        assertEquals(home + "/.biblioteca/lucene-index", env.getProperty("lucene.index.dir"));
    }

    @Test
    void absoluteAndDefaultWorkDirsAreLeftAlone() {
        assertEquals("/work", environment("/work").getProperty("work.dir"));
        assertEquals("/work", environment(null).getProperty("work.dir"));
        assertEquals("/work/cache", environment(null).getProperty("cache.dir"));
    }
}
