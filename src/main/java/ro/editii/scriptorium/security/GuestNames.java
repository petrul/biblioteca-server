package ro.editii.scriptorium.security;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hands out guest names - "guest_formidable_dictator" - for readers who are
 * not signed in: the reader's browser asks for one on its first visit (see
 * GuestRestController) and keeps it, so one guest's browsing reads as one
 * story in every service's log (see {@link Initiator}). The word lists are
 * roundsq-server's (its WordService), normalized the same way; they live only
 * here, so no other service or browser bundle carries them.
 */
@Component
public class GuestNames {

    private final String[] adjectives = words("words/adjectives.txt");
    private final String[] nouns = words("words/nouns.txt");

    public String newName() {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        return Initiator.GUEST_PREFIX + this.adjectives[random.nextInt(this.adjectives.length)]
                + "_" + this.nouns[random.nextInt(this.nouns.length)];
    }

    /** One word per line, lowercased, apostrophes and hyphens dropped (as roundsq does). */
    static String[] words(String resource) {
        try (InputStream in = GuestNames.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing word list " + resource);
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                    .map(line -> line.replace("'", "").replace("-", "").trim().toLowerCase(Locale.ROOT))
                    .filter(word -> word.matches("[a-z]+"))
                    .distinct()
                    .toArray(String[]::new);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
