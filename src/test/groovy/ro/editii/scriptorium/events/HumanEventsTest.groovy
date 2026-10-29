package ro.editii.scriptorium.events

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.util.UriComponentsBuilder
import ro.editii.scriptorium.dto.UserLoggedInDto
import ro.editii.scriptorium.kafka.KafkaProducer
import ro.editii.scriptorium.kafka.KafkaProps
import ro.editii.scriptorium.kafka.KafkaTextbaseEventsPublisher
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.rest.SearchRestController
import ro.editii.scriptorium.service.DbSearchService

import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Interesting human events go to ONE Kafka topic, biblioteca_events, as flat
 * JSON - no settings, no secrets, no Greg-specific topics:
 *
 *   type     published by                    details
 *   -------  ------------------------------  ---------------------------------------
 *   login    biblioteca-server (every login)  provider, userId
 *   search   biblioteca-server (5 modes)      mode, query, limit, results
 *   chat, rag-search, ask - biblioteca-nestjs, see its human-events.publisher.spec.ts
 *
 * Every event carries type, at and service; user only when signed in.
 */
class HumanEventsTest {

    @AfterEach
    void signOut() { SecurityContextHolder.clearContext() }

    /** A KafkaProducer that records what it is asked to send instead of sending it. */
    private static KafkaProducer recordingProducer(List sent, boolean failing = false) {
        new KafkaProducer(null) {
            @Override
            void sendAsJson(String topic, Object object) {
                if (failing) throw new IllegalStateException('Kafka is down')
                sent << [topic: topic, message: object]
            }
        }
    }

    @Test
    void theTopicIsTheConstantBibliotecaEventsAndNothingIsConfiguredFromTheEnvironment() {
        assertEquals('biblioteca_events', new KafkaProps().humanEventsTopicName)
        final settings = KafkaProps.declaredFields.findAll { it.isAnnotationPresent(Value) }*.name
        assertEquals(['bootstrapServers'], settings, 'no mail/ntfy destinations or tokens - only the broker address')
    }

    @Test
    void anEventIsFlatJsonWithTypeTimeServiceThenItsOwnDetails() {
        final event = HumanEvents.event('search', 'petru@scriptorium.ro', [mode: 'lucene', query: 'Angelica', missing: null])
        assertEquals(['type', 'at', 'service', 'user', 'mode', 'query'], event.keySet() as List)
        assertEquals('biblioteca-server', event.service)
        Instant.parse(event.at as String)
        assertFalse(HumanEvents.event('search', null, [:]).containsKey('user'), 'anonymous: no user field')
    }

    @Test
    void aLoginIsPublishedOnBibliotecaEvents() {
        final sent = []
        new KafkaTextbaseEventsPublisher(recordingProducer(sent), new KafkaProps())
                .signalUserLoggedIn(UserLoggedInDto.builder().userId(7L).username('petru@scriptorium.ro').provider('google').loginAt(Instant.now()).build())

        assertEquals(['biblioteca_loginTopic', 'biblioteca_events'], sent*.topic)
        final event = sent[1].message
        assertEquals([type: 'login', user: 'petru@scriptorium.ro', provider: 'google', userId: 7L],
                event.subMap(['type', 'user', 'provider', 'userId']))
    }

    @Test
    void aFailingKafkaNeverFailsTheRequestTheEventRecords() {
        new KafkaTextbaseEventsPublisher(recordingProducer([], true), new KafkaProps())
                .signalHumanEvent(HumanEvents.event('search', null, [:]))
    }

    @Test
    void everySearchModeIsPublishedWithItsQueryEffectiveLimitAndResultCount() {
        final events = []
        final search = new DbSearchService(null, null, null, null, null) {
            List findAuthors(String q, int limit) { [] }
            List findDivHeads(String q, int limit) { [] }
            List searchMilvus(String q, int limit) { [] }
            List searchGrep(String q, int limit) { [] }
            List searchLucene(String q, int limit) { [] }
        }
        final controller = new SearchRestController(new MockEnvironment(), search, null, null)
        controller.events = [signalHumanEvent: { Map event -> events << event }] as TextbaseEventsPublisher
        final ucb = UriComponentsBuilder.fromUriString('http://test')

        controller.searchLucene('Angelica', 10, ucb)
        SecurityContextHolder.context.authentication = new UsernamePasswordAuthenticationToken('petru@scriptorium.ro', null, [])
        controller.searchAuthors('ariosto', 1_000_000, ucb, new MockHttpServletRequest())
        controller.searchDivHeads('canto', 20, ucb)
        controller.searchVector('furia', 20, ucb)
        controller.searchGrep('Orlando', 20)

        assertEquals(['lucene', 'authors', 'divHeads', 'vector', 'grep'], events*.mode)
        assertTrue(events.every { it.type == 'search' && it.results == 0 })
        assertEquals([type: 'search', mode: 'lucene', query: 'Angelica', limit: 10, results: 0], events[0].subMap(['type', 'mode', 'query', 'limit', 'results']))
        assertFalse(events[0].containsKey('user'), 'anonymous search')
        assertEquals('petru@scriptorium.ro', events[1].user)
        assertEquals(500, events[1].limit, 'the limit after the server cap, not what was asked')
    }
}
