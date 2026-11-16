package ro.editii.scriptorium.rest

import org.junit.jupiter.api.Test
import org.springframework.web.util.UriComponentsBuilder

class AuthorSourceImagesTest {

    @Test
    void bundledAuthorImagesAreNoLongerAdvertised() {
        def util = new CommonControllerUtil(null)
        def uri = UriComponentsBuilder.fromUriString('http://localhost:8080')

        assert util.getAuthorImage('comnena', uri, null) == null
        assert util.getAuthorThumb('comnena', uri, null) == null
    }
}
