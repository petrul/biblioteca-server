package ro.editii.scriptorium;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Registers TestTeiRepoGuard for every @SpringBootTest context, via
 * META-INF/spring.factories on the test classpath - see TestTeiRepoGuard
 * for what it enforces and why it must not require per-test declaration.
 */
public class TestTeiRepoGuardContextCustomizerFactory implements ContextCustomizerFactory {

    private enum GuardCustomizer implements ContextCustomizer {
        INSTANCE;

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            context.addBeanFactoryPostProcessor(new TestTeiRepoGuard());
        }
    }

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        return GuardCustomizer.INSTANCE;
    }
}
