package ro.editii.scriptorium.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
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
 * Every log message starts with its initiator (see Initiator): who it is,
 * and which callers may say so.
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
    void 'a guest name is believed from anyone, a user name or system only from an internal caller'() {
        assert Initiator.received('guest_formidable_dictator', false) == Optional.of('guest_formidable_dictator')
        assert Initiator.received('petru@scriptorium.ro', false) == Optional.empty()
        assert Initiator.received('petru@scriptorium.ro', true) == Optional.of('petru@scriptorium.ro')
        assert Initiator.received('system', true) == Optional.of('system'), "an internal caller's background work"
        assert Initiator.received('system', false) == Optional.empty()
        assert Initiator.received('guest_Formidable_dictator', false) == Optional.empty(), 'not a guest name'
        ['anonymous', 'petru) (admin', 'a\nFAKE LINE', '', null].each {
            assert Initiator.received(it, true) == Optional.empty(), it
        }
    }

    @Test
    void 'a user name is kept to the label characters'() {
        assert Initiator.user('petru') == 'petru'
        assert Initiator.user('petru@scriptorium.ro') == 'petru@scriptorium.ro'
        assert Initiator.user('evil)\n(admin') == 'evil___admin'
    }

    private String labelFor(Map<String, String> headers, Cookie... cookies) {
        def request = new MockHttpServletRequest('GET', '/api/works')
        headers.each { name, value -> request.addHeader(name, value) }
        if (cookies) request.setCookies(cookies)
        String seen = null
        filter.doFilter(request, new MockHttpServletResponse(), { req, res -> seen = MDC.get(Initiator.MDC_KEY) } as FilterChain)
        return seen
    }

    @Test
    void 'a request logs as the label its caller may give, else its guest cookie, else anonymous'() {
        assert labelFor([:]) == 'anonymous'
        assert labelFor([:], new Cookie('bib_guest', 'guest_formidable_dictator')) == 'guest_formidable_dictator'
        assert labelFor([:], new Cookie('bib_guest', 'petru@scriptorium.ro')) == 'anonymous'
        assert labelFor([(Initiator.HEADER): 'guest_amber_otter'], new Cookie('bib_guest', 'guest_formidable_dictator')) == 'guest_amber_otter'
        assert labelFor([(Initiator.HEADER): 'petru@scriptorium.ro']) == 'anonymous'
        assert labelFor([(Initiator.HEADER): 'petru@scriptorium.ro', Authorization: 'Bearer internal-secret']) == 'petru@scriptorium.ro'
    }

    @Test
    void 'the label outlives the request - Tomcat logs uncaught exceptions after the filters'() {
        labelFor([(Initiator.HEADER): 'guest_amber_otter'])
        assert MDC.get(Initiator.MDC_KEY) == 'guest_amber_otter'
    }

    private MockHttpServletResponse authenticatedAs(def authentication, Closure during = {}) {
        MDC.put(Initiator.MDC_KEY, 'guest_amber_otter')
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
        assert MDC.get(Initiator.MDC_KEY) == 'guest_amber_otter'
        assert response.getHeader(Initiator.HEADER) == 'guest_amber_otter'
    }

    @Test
    void 'a sign-in answers with the user it signed in'() {
        def response = authenticatedAs(null) {
            SecurityContextHolder.context.authentication =
                    new UsernamePasswordAuthenticationToken('petru@scriptorium.ro', null, AuthorityUtils.NO_AUTHORITIES)
        }
        assert response.getHeader(Initiator.HEADER) == 'petru@scriptorium.ro'
    }

    @Test
    void 'guest names come from the word lists and are guests everywhere'() {
        def names = new GuestNames()
        def name = names.newName()
        assert Initiator.isGuest(name), name
        assert (1..20).collect { names.newName() }.toSet().size() > 15, 'about 2.5 billion names: no repeats in a few'
        assert GuestNames.words('words/adjectives.txt').contains('formidable')
        assert GuestNames.words('words/nouns.txt').contains('dictator')
    }
}
