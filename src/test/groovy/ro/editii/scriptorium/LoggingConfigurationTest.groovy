package ro.editii.scriptorium

import ch.qos.logback.classic.pattern.TargetLengthBasedClassNameAbbreviator
import org.junit.jupiter.api.Test

class LoggingConfigurationTest {

    @Test
    void 'abbreviates logger package names in console and file output'() {
        def properties = new Properties()
        getClass().getResourceAsStream('/application.properties').withCloseable {
            properties.load(it)
        }

        assert properties.getProperty('logging.pattern.console').contains('logger{15}')
        assert properties.getProperty('logging.pattern.file').contains('logger{15}')
        assert new TargetLengthBasedClassNameAbbreviator(15)
            .abbreviate('ro.editii.scriptorium.model.TeiElem') == 'r.e.s.m.TeiElem'
    }
}
