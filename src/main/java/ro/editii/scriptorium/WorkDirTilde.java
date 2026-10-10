package ro.editii.scriptorium;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Expands a leading ~ in work.dir once, before any bean reads it. Spring
 * does not expand ~, and a machine profile may set WORK_DIR=~/.biblioteca
 * (zmeu's does): without this, every ${work.dir}-derived path (cache.dir,
 * lucene.index.dir, the git-repos checkouts, the session store) had to call
 * Util.replaceTilde itself, and one that did not (GitTeiRepoImpl) would have
 * created a directory literally named "~" under the current directory.
 */
public class WorkDirTilde implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE = "workDirTilde";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        final String workDir = environment.getProperty("work.dir");
        if (workDir == null || !workDir.startsWith("~")) {
            return;
        }
        // First, so it wins over the placeholder definition in application.properties.
        environment.getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE, Map.of("work.dir", Util.replaceTilde(workDir))));
    }
}
