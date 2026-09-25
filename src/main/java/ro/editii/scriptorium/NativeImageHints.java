package ro.editii.scriptorium;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Classpath resources read from Author's static initializer via
 * ClassLoader#getResourceAsStream (Util.readRecommendedAuthorMappings and
 * friends) - native-image only embeds resources the AOT metadata knows
 * about, so without these patterns Author fails its static init inside
 * the graalvm binary and the JPA layer cannot boot (observed with the
 * 0.9.6-SNAPSHOT-graalvm smoke test: "Unable to load class
 * [ro.editii.scriptorium.model.Author]").
 */
public class NativeImageHints implements RuntimeHintsRegistrar {

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		hints.resources().registerPattern("recommended-author-urls.properties");
		hints.resources().registerPattern("forbidden-author-names.txt");
		hints.resources().registerPattern("special-authors.properties");
	}

}
