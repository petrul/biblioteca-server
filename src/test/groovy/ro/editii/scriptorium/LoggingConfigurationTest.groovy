package ro.editii.scriptorium

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.pattern.TargetLengthBasedClassNameAbbreviator
import ch.qos.logback.classic.spi.LoggingEvent
import org.junit.jupiter.api.Test
import org.slf4j.MDC

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

    @Test
    void 'every line starts with its initiator - system outside a request'() {
        def properties = new Properties()
        getClass().getResourceAsStream('/application.properties').withCloseable {
            properties.load(it)
        }
        def context = new LoggerContext()
        context.setMDCAdapter(MDC.getMDCAdapter())
        def render = { String key ->
            def layout = new PatternLayout(context: context, pattern: properties.getProperty(key).replace('${PID:}', '1'))
            layout.start()
            def event = new LoggingEvent('test', context.getLogger('ro.editii.scriptorium.model.TeiElem'), Level.INFO, 'parsed', null, null)
            layout.doLayout(event)
        }

        MDC.remove('initiator')
        assert render('logging.pattern.console').startsWith('(system) ')
        try {
            MDC.put('initiator', 'petru@scriptorium.ro')
            assert render('logging.pattern.console').startsWith('(petru@scriptorium.ro) ')
            assert render('logging.pattern.file').startsWith('(petru@scriptorium.ro) ')
        } finally {
            MDC.remove('initiator')
        }
    }
}
