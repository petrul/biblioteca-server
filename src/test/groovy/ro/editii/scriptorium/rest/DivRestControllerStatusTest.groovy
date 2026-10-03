package ro.editii.scriptorium.rest

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * Regression tests for the REST error boundary.
 *
 * These deliberately avoid starting Spring or connecting to a database: the
 * bug was a status-code translation in DivRestController itself.
 */
class DivRestControllerStatusTest {

    private final DivRestController controller = new DivRestController(null, null, null, null)

    @Test
    void preservesNotFoundForMissingOpus() {
        def response = controller.handleTeiFailure(
                new ResponseStatusException(HttpStatus.NOT_FOUND, 'no opus for [verne/castelul_din_carpati]'))

        assertEquals(404, response.statusCode.value())
        assertEquals(true, response.body.contains('no opus for [verne/castelul_din_carpati]'))
    }

    @Test
    void preservesNotFoundWhenTheExceptionIsWrapped() {
        def missing = new ResponseStatusException(HttpStatus.NOT_FOUND, 'no opus for [missing/opus]')
        def response = controller.handleTeiFailure(new RuntimeException('request failed', missing))

        assertEquals(404, response.statusCode.value())
    }

    @Test
    void keepsUnexpectedFailuresAsServerErrors() {
        def response = controller.handleTeiFailure(new IllegalStateException('unexpected failure'))

        assertEquals(500, response.statusCode.value())
    }
}
