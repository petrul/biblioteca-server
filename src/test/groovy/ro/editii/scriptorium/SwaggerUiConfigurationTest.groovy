package ro.editii.scriptorium

import org.junit.jupiter.api.Test

class SwaggerUiConfigurationTest {

    @Test
    void swaggerUiUsesThePublishedOpenApiConfiguration() {
        final properties = new Properties()
        getClass().getResourceAsStream('/application.properties').withCloseable {
            properties.load(it)
        }

        final apiDocsPath = properties.getProperty('springdoc.api-docs.path')

        assert properties.getProperty('springdoc.swagger-ui.path') == '/api/ui'
        assert properties.getProperty('springdoc.swagger-ui.configUrl') == "${apiDocsPath}/swagger-config"
        assert properties.getProperty('springdoc.swagger-ui.url') == apiDocsPath
    }
}
