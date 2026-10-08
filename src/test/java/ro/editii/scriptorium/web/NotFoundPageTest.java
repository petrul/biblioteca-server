package ro.editii.scriptorium.web;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders templates/error/404.html through a bare SpringTemplateEngine - no
 * full Spring context, no DB - the same classpath-relative resolution
 * Spring Boot's own auto-configured ErrorViewResolver uses to pick this
 * template up for any unhandled 404 across the app. Exists because the
 * full @SpringBootTest route requires a live DB connection this template
 * itself has nothing to do with. Spring's engine specifically (not plain
 * org.thymeleaf.TemplateEngine): the app runs SpringStandardDialect via
 * SpEL, not StandardDialect via OGNL, which isn't on this project's
 * classpath at all - using the plain engine fails immediately on any
 * expression with NoClassDefFoundError: ognl/PropertyAccessor.
 */
class NotFoundPageTest {

    @Test
    void the404TemplateRendersAndLinksTheUploadedArt() {
        final var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");

        final var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        final String html = engine.process("error/404", new Context());

        assertTrue(html.contains("/404.png"), "expected the page to reference the uploaded /404.png");
        assertTrue(html.contains("404"), "expected the page to mention the error code somewhere");
        assertTrue(html.toLowerCase().contains("library") || html.toLowerCase().contains("biblioteca"),
                "expected a way back to the site, not a dead end");
    }
}
