package ro.editii.scriptorium;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * What the GraalVM native image must know about ahead of time (the JVM
 * build needs none of this).
 *
 * Classpath resources read from Author's static initializer via
 * ClassLoader#getResourceAsStream (Util.readRecommendedAuthorMappings and
 * friends) - native-image only embeds resources the AOT metadata knows
 * about, so without these patterns Author fails its static init inside
 * the graalvm binary and the JPA layer cannot boot (observed with the
 * 0.9.6-SNAPSHOT-graalvm smoke test: "Unable to load class
 * [ro.editii.scriptorium.model.Author]").
 *
 * ICU4J (Util's static initializer builds a Russian-Latin transliterator,
 * and Author's reads Util): ICU reads Locale.getDefault(Category)
 * reflectively and loads its data files - without both, Util fails to
 * initialize, then Author, which is what 0.9.16-graalvm's "Unable to load
 * class [ro.editii.scriptorium.model.Author]" really was. Its reflection
 * comes from the tracing-agent metadata in
 * META-INF/native-image/ro.editii/biblioteca-server (native/README.md).
 *
 * Serialization of what a sign-in keeps in the servlet session: sessions
 * are persisted under work.dir/sessions and reloaded on start
 * (SessionConfig), and native-image serializes only registered types -
 * 0.9.16-graalvm failed restoring them ("Cannot reflectively read or write
 * field ... SecurityContextImpl.serialVersionUID"), signing everyone out.
 * GoogleAuthController stores a SecurityContextImpl holding a
 * UsernamePasswordAuthenticationToken of a userdetails User with its
 * roles; Spring Security may also keep a request saved for after sign-in.
 */
public class NativeImageHints implements RuntimeHintsRegistrar {

	static final String[] SESSION_TYPES = {
			"org.springframework.security.core.context.SecurityContextImpl",
			"org.springframework.security.authentication.AbstractAuthenticationToken",
			"org.springframework.security.authentication.UsernamePasswordAuthenticationToken",
			"org.springframework.security.core.userdetails.User",
			"org.springframework.security.core.userdetails.User$AuthorityComparator",
			"org.springframework.security.core.authority.SimpleGrantedAuthority",
			"org.springframework.security.web.authentication.WebAuthenticationDetails",
			"org.springframework.security.web.savedrequest.DefaultSavedRequest",
			"org.springframework.security.web.savedrequest.SavedCookie",
			"java.util.ArrayList",
			"java.util.TreeSet",
			"java.util.TreeMap",
			"java.util.HashMap",
			"java.util.LinkedHashMap",
			"java.util.HashSet",
			"java.util.Locale",
			"java.util.Collections$UnmodifiableCollection",
			"java.util.Collections$UnmodifiableSet",
			"java.util.Collections$UnmodifiableSortedSet",
			"java.util.Collections$UnmodifiableList",
			"java.util.Collections$UnmodifiableRandomAccessList",
			"java.util.Collections$EmptyList",
			"java.util.Collections$EmptySet",
			"java.util.Collections$EmptyMap",
			"java.lang.String",
			"java.lang.Integer",
			"java.lang.Long",
			"java.lang.Boolean",
			"java.lang.Number",
			"java.lang.String[]",
	};

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		hints.resources().registerPattern("recommended-author-urls.properties");
		hints.resources().registerPattern("forbidden-author-names.txt");
		hints.resources().registerPattern("special-authors.properties");
		// ICU4J's data (Util's Cyrillic transliterator and every other ICU
		// service): all of it, not only the files the tracing agent saw opened
		// (native/reachability metadata) - a missing one only shows at run time.
		hints.resources().registerPattern("com/ibm/icu/ICUConfig.properties");
		hints.resources().registerPattern("com/ibm/icu/impl/data/icudt71b/**");
		for (String type : SESSION_TYPES)
			hints.serialization().registerType(TypeReference.of(type));
	}

}
