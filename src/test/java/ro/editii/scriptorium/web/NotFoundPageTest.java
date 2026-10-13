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
    void the404TemplateRendersAndLinksTheServerArt() {
        final String html = render("error/404");

        assertTrue(html.contains("/404.jpeg"), "expected the page to reference the server's 404 art");
        assertTrue(getClass().getClassLoader().getResource("static/404.jpeg") != null,
                "expected the referenced 404 image to be packaged as a static resource");
        assertTrue(html.contains("404"), "expected the page to mention the error code somewhere");
        assertTrue(html.toLowerCase().contains("library") || html.toLowerCase().contains("biblioteca"),
                "expected a way back to the site, not a dead end");
    }

    @Test
    void the5xxTemplateRendersAndLinksTheServerArt() {
        final String html = render("error/5xx");

        assertTrue(html.contains("/500.jpeg"), "expected the page to reference the server's 5xx art");
        assertTrue(getClass().getClassLoader().getResource("static/500.jpeg") != null,
                "expected the referenced 5xx image to be packaged as a static resource");
        assertTrue(html.contains("5xx"), "expected the page to identify a server error");
    }

    private String render(String template) {
        final var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");

        final var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        return engine.process(template, new Context());
    }
}
