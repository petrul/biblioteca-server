package ro.editii.scriptorium.security

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Every log line starts with its initiator (see Initiator): who it is, and
 * which callers may say so.
 */
class InitiatorTest {

    final InternalApiToken token = new InternalApiToken('internal-secret')
    final InitiatorFilter filter = new InitiatorFilter(token)

    @AfterEach
    void clear() {
        MDC.remove(Initiator.MDC_KEY)
        SecurityContextHolder.clearContext()
    }

    @Test
    void 'a guest label is human-readable, stable, and the same as the reader derives'() {
        assert Initiator.guestLabel('visitor-1') ==~ /guest-[a-z]+-[a-z]+-[0-9a-f]{4}/
        assert Initiator.guestLabel('visitor-1') != Initiator.guestLabel('visitor-2')
        // biblioteca-reader's initiator.test.ts pins the same seed to the same label
        assert Initiator.guestLabel('visitor-1') == 'guest-witty-owl-38c6'
    }

    @Test
    void 'a guest label is believed from anyone, a user name only from an internal caller'() {
        assert Initiator.received('guest-amber-otter-3f2a', false) == Optional.of('guest-amber-otter-3f2a')
        assert Initiator.received('petru@scriptorium.ro', false) == Optional.empty()
        assert Initiator.received('petru@scriptorium.ro', true) == Optional.of('petru@scriptorium.ro')
        assert Initiator.received('system', true) == Optional.of('system'), "an internal caller's background work"
        assert Initiator.received('system', false) == Optional.empty()
        ['petru) (admin', 'a\nFAKE LINE', '', null].each {
            assert Initiator.received(it, true) == Optional.empty(), it
        }
    }

    @Test
    void 'a user name is kept to the label characters'() {
        assert Initiator.user('petru') == 'petru'
        assert Initiator.user('petru@scriptorium.ro') == 'petru@scriptorium.ro'
        assert Initiator.user('evil)\n(admin') == 'evil___admin'
    }

    private String labelFor(Map<String, String> headers) {
        def request = new MockHttpServletRequest('GET', '/api/works')
        request.remoteAddr = '192.168.0.7'
        headers.each { name, value -> request.addHeader(name, value) }
        String seen = null
        filter.doFilter(request, new MockHttpServletResponse(), { req, res -> seen = MDC.get(Initiator.MDC_KEY) } as FilterChain)
        return seen
    }

    @Test
    void 'a request logs as the label its caller may give, else as the guest of its address and browser'() {
        assert labelFor(['User-Agent': 'Firefox']) == Initiator.guestLabel('192.168.0.7|Firefox')
        assert labelFor([(Initiator.HEADER): 'guest-amber-otter-3f2a']) == 'guest-amber-otter-3f2a'
        assert labelFor([(Initiator.HEADER): 'petru@scriptorium.ro', 'User-Agent': 'curl']) == Initiator.guestLabel('192.168.0.7|curl')
        assert labelFor([(Initiator.HEADER): 'petru@scriptorium.ro', Authorization: 'Bearer internal-secret']) == 'petru@scriptorium.ro'
    }

    @Test
    void 'the label outlives the request - Tomcat logs uncaught exceptions after the filters'() {
        labelFor([(Initiator.HEADER): 'guest-amber-otter-3f2a'])
        assert MDC.get(Initiator.MDC_KEY) == 'guest-amber-otter-3f2a'
    }

    private MockHttpServletResponse authenticatedAs(def authentication, Closure during = {}) {
        MDC.put(Initiator.MDC_KEY, 'guest-amber-otter-3f2a')
        if (authentication) SecurityContextHolder.context.authentication = authentication
        def response = new MockHttpServletResponse()
        new InitiatorAuthenticationFilter().doFilter(new MockHttpServletRequest('GET', '/api/users/me'), response,
                { req, res -> during() } as FilterChain)
        return response
    }

    @Test
    void 'a signed-in request logs as its user, and says so to the caller'() {
        def response = authenticatedAs(new UsernamePasswordAuthenticationToken('petru@scriptorium.ro', null, AuthorityUtils.NO_AUTHORITIES))
        assert MDC.get(Initiator.MDC_KEY) == 'petru@scriptorium.ro'
        assert response.getHeader(Initiator.HEADER) == 'petru@scriptorium.ro'
    }

    @Test
    void 'an anonymous request stays its guest'() {
        def response = authenticatedAs(new AnonymousAuthenticationToken('key', 'anonymousUser', AuthorityUtils.createAuthorityList('ROLE_ANONYMOUS')))
        assert MDC.get(Initiator.MDC_KEY) == 'guest-amber-otter-3f2a'
        assert response.getHeader(Initiator.HEADER) == 'guest-amber-otter-3f2a'
    }

    @Test
    void 'a sign-in answers with the user it signed in'() {
        def response = authenticatedAs(null) {
            SecurityContextHolder.context.authentication =
                    new UsernamePasswordAuthenticationToken('petru@scriptorium.ro', null, AuthorityUtils.NO_AUTHORITIES)
        }
        assert response.getHeader(Initiator.HEADER) == 'petru@scriptorium.ro'
    }
}
