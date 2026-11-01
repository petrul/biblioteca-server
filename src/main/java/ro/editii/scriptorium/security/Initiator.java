package ro.editii.scriptorium.security;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Who a log line is for. Every line this server logs names its initiator
 * just before the message - "(petru@scriptorium.ro) API GET ...",
 * "(guest_formidable_dictator) ..." for a reader who is not signed in but
 * has a guest name (see {@link GuestNames}), "(anonymous) ..." for a request
 * without one, or "(system) ..." for work no request started (startup,
 * Kafka listeners, schedulers): the {@link #MDC_KEY} MDC value in
 * logging.pattern.*. The label travels between the Biblioteca services in
 * the {@link #HEADER} header, and this server answers every request with the
 * one it resolved - which is how biblioteca-reader learns who a session is.
 */
public final class Initiator {

    public static final String HEADER = "X-Biblioteca-Initiator";
    /** The reader's cookie that keeps a browser's guest name. */
    public static final String GUEST_COOKIE = "bib_guest";
    public static final String MDC_KEY = "initiator";
    public static final String SYSTEM = "system";
    public static final String ANONYMOUS = "anonymous";
    public static final String GUEST_PREFIX = "guest_";

    private static final Pattern GUEST = Pattern.compile("guest_[a-z]{1,40}_[a-z]{1,40}");
    /** a user name: an email, or a plain name like the seeded admin's */
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._%+@-]{1,254}");
    private static final Pattern NOT_USER_CHAR = Pattern.compile("[^A-Za-z0-9._%+@-]");

    private Initiator() {
    }

    public static boolean isGuest(String label) {
        return label != null && GUEST.matcher(label).matches();
    }

    /**
     * The label another service sent, if it may be believed: a guest name
     * from anyone; a user name, or "system" for that service's own
     * background work, only from a caller holding the internal API token -
     * the reader's browsers must not be able to log as someone else. Only
     * these shapes pass, so a header can never inject text into a log.
     */
    public static Optional<String> received(String header, boolean internalCaller) {
        if (header == null) return Optional.empty();
        if (isGuest(header)) return Optional.of(header);
        if (!internalCaller || ANONYMOUS.equals(header)) return Optional.empty();
        return SYSTEM.equals(header) || USER.matcher(header).matches() ? Optional.of(header) : Optional.empty();
    }

    /** A signed-in user's label: the user name, restricted to the label characters. */
    public static String user(String username) {
        final String label = NOT_USER_CHAR.matcher(username).replaceAll("_");
        return label.isEmpty() ? "_" : label.substring(0, Math.min(label.length(), 254));
    }
}
