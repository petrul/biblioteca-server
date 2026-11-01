package ro.editii.scriptorium.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Who a log line is for. Every line this server logs starts with its
 * initiator - "(petru@scriptorium.ro)", "(guest-amber-otter-3f2a)" for a
 * reader who is not signed in, or "(system)" for work no request started
 * (startup, Kafka listeners, schedulers): the {@link #MDC_KEY} MDC value in
 * logging.pattern.*. The label travels between the Biblioteca services in
 * the {@link #HEADER} header, and this server answers every request with the
 * one it resolved - which is how biblioteca-reader learns who a session is.
 *
 * Guest labels are derived exactly like biblioteca-reader's initiator.ts
 * (same word lists, same hash), so a seed gives the same guest everywhere.
 */
public final class Initiator {

    public static final String HEADER = "X-Biblioteca-Initiator";
    public static final String MDC_KEY = "initiator";
    public static final String SYSTEM = "system";

    private static final List<String> ADJECTIVES = List.of(
            "amber", "azure", "bold", "brave", "bright", "calm", "clever", "coral",
            "crimson", "dusky", "eager", "gentle", "golden", "hidden", "ivory", "jolly",
            "keen", "lively", "lucky", "merry", "misty", "noble", "olive", "quiet",
            "rapid", "rosy", "silver", "steady", "sunny", "swift", "velvet", "witty");
    private static final List<String> NOUNS = List.of(
            "amphora", "badger", "basilica", "beacon", "comet", "discus", "falcon", "fox",
            "heron", "lantern", "lark", "lynx", "meadow", "otter", "owl", "panther",
            "pebble", "quill", "raven", "river", "robin", "scroll", "sparrow", "stag",
            "stylus", "swan", "temple", "thistle", "tiger", "vellum", "willow", "wren");

    private static final Pattern GUEST = Pattern.compile("guest-[a-z]+-[a-z]+-[0-9a-f]{4}");
    /** a user name: an email, or a plain name like the seeded admin's */
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._%+@-]{1,254}");
    private static final Pattern NOT_USER_CHAR = Pattern.compile("[^A-Za-z0-9._%+@-]");

    private Initiator() {
    }

    /** A stable, human-readable label for a reader who is not signed in. */
    public static String guestLabel(String seed) {
        final byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return "guest-" + ADJECTIVES.get(Byte.toUnsignedInt(hash[0]) % ADJECTIVES.size())
                + "-" + NOUNS.get(Byte.toUnsignedInt(hash[1]) % NOUNS.size())
                + "-" + HexFormat.of().formatHex(hash, 2, 4);
    }

    public static boolean isGuest(String label) {
        return label != null && GUEST.matcher(label).matches();
    }

    /**
     * The label another service sent, if it may be believed: a guest label
     * from anyone; a user name, or "system" for that service's own
     * background work, only from a caller holding the internal API token -
     * the reader's browsers must not be able to log as someone else. Only
     * these shapes pass, so a header can never inject text into a log.
     */
    public static Optional<String> received(String header, boolean internalCaller) {
        if (header == null) return Optional.empty();
        if (isGuest(header)) return Optional.of(header);
        if (SYSTEM.equals(header)) return internalCaller ? Optional.of(SYSTEM) : Optional.empty();
        return internalCaller && USER.matcher(header).matches() ? Optional.of(header) : Optional.empty();
    }

    /** A signed-in user's label: the user name, restricted to the label characters. */
    public static String user(String username) {
        final String label = NOT_USER_CHAR.matcher(username).replaceAll("_");
        return label.isEmpty() ? "_" : label.substring(0, Math.min(label.length(), 254));
    }
}
