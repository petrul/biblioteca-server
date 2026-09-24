package ro.editii.scriptorium;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Fails any Spring test context that would wire the production TEI
 * repositories: AppConfig's teiRepo bean, driven by repo.tei.repos
 * (TEI_REPOS) - i.e. real, possibly huge local checkouts or git clones of
 * the real corpus. Tests must use a fixture TeiRepo instead (TestConfig's
 * src/test/resources/testrepo, MultilangTeiRepoConfig's testrepo-search,
 * or an in-memory equivalent) - every existing test context already does,
 * via spring.main.allow-bean-definition-overriding=true; this guard turns
 * that convention into a hard failure the moment a new context forgets
 * it and silently boots against real data.
 *
 * Registered for every @SpringBootTest context via META-INF/spring.factories
 * (TestTeiRepoGuardContextCustomizerFactory) - it needs no per-test
 * declaration, which is the point: the failure mode it guards against is
 * precisely a test that DOESN'T declare the fixture.
 */
public class TestTeiRepoGuard implements BeanFactoryPostProcessor {

    private static final String TEI_REPO_BEAN_NAME = "teiRepo";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!beanFactory.containsBeanDefinition(TEI_REPO_BEAN_NAME)) {
            return; // narrow contexts (vector, parser) wire no TeiRepo at all
        }
        final BeanDefinition teiRepo = beanFactory.getBeanDefinition(TEI_REPO_BEAN_NAME);
        final String factoryBeanName = teiRepo.getFactoryBeanName();
        if (factoryBeanName == null) {
            return; // not a @Bean method of a configuration class - nothing to attribute
        }
        final String declaringClass = beanFactory.containsBeanDefinition(factoryBeanName)
                ? beanFactory.getBeanDefinition(factoryBeanName).getBeanClassName()
                : null;
        if (AppConfig.class.getName().equals(declaringClass) || "appConfig".equals(factoryBeanName)) {
            throw new IllegalStateException(
                    "Test context wired the production TEI repositories (AppConfig.teiRepo, repo.tei.repos/TEI_REPOS) - "
                    + "tests must never reach out to real repositories. Add TestConfig (or another fixture TeiRepo config) "
                    + "to this test's @SpringBootTest(classes=...) so the fixture repo overrides the production bean.");
        }
    }
}
