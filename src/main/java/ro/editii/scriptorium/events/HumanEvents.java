package ro.editii.scriptorium.events;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Interesting human events - a login, a search, a chat - published as JSON on
 * the biblioteca_events Kafka topic (KafkaProps.humanEventsTopicName), the one
 * place anything downstream (mail, push, statistics) picks them up from. No
 * configuration: the topic name is a constant, never a secret or a setting.
 * <p>
 * Every event is a flat JSON object:
 * <pre>
 * {"type":"search","at":"2026-10-29T10:15:02Z","service":"biblioteca-server",
 *  "user":"petru@scriptorium.ro","mode":"lucene","query":"Angelica","limit":10,"results":7}
 * </pre>
 * type, at and service are always present; user is the signed-in reader, or
 * absent for an anonymous one; the rest depends on the type. HumanEventsTest
 * pins the shapes.
 */
public final class HumanEvents {

    public static final String SERVICE = "biblioteca-server";

    private HumanEvents() {}

    /** An event of `type` by `user` (null: anonymous), with its own details after the common fields. */
    public static Map<String, Object> event(String type, String user, Map<String, ?> details) {
        final Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("at", Instant.now().toString());
        event.put("service", SERVICE);
        if (user != null) event.put("user", user);
        details.forEach((key, value) -> { if (value != null) event.put(key, value); });
        return event;
    }

    /** The signed-in user of the current request, or null for an anonymous one. */
    public static String currentUser() {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return null;
        return authentication.getName();
    }
}
