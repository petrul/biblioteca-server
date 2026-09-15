package ro.editii.scriptorium.enrichment;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.health.OllamaHealthTracker;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.Languages;
import ro.editii.scriptorium.model.TeiDiv;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Best-effort background enrichment: a short author bio or opus summary,
 * synthesized from real-world material via SearXNG (self-hosted
 * metasearch, docker/searxng in the scripts repo) + Ollama (a plain
 * generative model, not the embedding-only ones VectorConfig configures).
 *
 * Every call here happens on its own virtual thread, fire-and-forget from
 * AdminService right after an opus/author is (re)imported - never blocks
 * the import itself, and any failure (SearXNG/Ollama unreachable, no
 * usable search results, whatever) is caught and logged, never thrown
 * back at the caller. Runs at most once per author/opus: both entities
 * only ever get enriched while their bio/summary is still null (see
 * AdminService), so this never re-spends an LLM call on every reimport,
 * and a manual edit to either field is never silently overwritten later.
 *
 * Source preference, per the user's own instruction: an established
 * reference source (Wikipedia, Britannica) beats generic search-result
 * noise. Wikipedia specifically gets its own REST summary API (a clean,
 * pre-condensed extract, no HTML scraping needed) rather than whatever
 * snippet SearXNG itself returns for it.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class AiEnrichmentService {

    private static final List<String> TRUSTED_DOMAINS = List.of("wikipedia.org", "britannica.com");
    private static final Pattern WIKIPEDIA_URL = Pattern.compile("https?://([a-z-]+)\\.wikipedia\\.org/wiki/([^#?]+)");

    // Wikimedia's API etiquette REQUIRES a descriptive User-Agent on every
    // request (see https://w.wiki/4wJS) - without one, both the REST
    // summary API and, empirically, even a stock RestTemplate GET get a
    // flat 403 rather than a real response.
    private static final String USER_AGENT =
            "TextbaseServer-AiEnrichment/1.0 (https://textbase.scriptorium.ro; self-hosted TEI library)";

    final RestTemplate restTemplate;
    @Qualifier("ollamaRestTemplate") final RestTemplate ollamaRestTemplate;
    final OllamaHealthTracker ollamaHealthTracker;
    final AuthorRepository authorRepository;
    final TeiDivRepository teiDivRepository;

    @Value("${searxng.host}") String searxngHost;
    @Value("${searxng.port}") int searxngPort;
    @Value("${enrichment.ollama.model}") String ollamaModel;
    @Value("${ollama.host}") String ollamaHost;
    @Value("${ollama.port}") int ollamaPort;
    // Off by default under the ci profile (application-ci.properties) -
    // every test class that imports the fixture corpus would otherwise
    // also fire real SearXNG/Wikipedia/Ollama calls per author/opus, on
    // every single test run. WebITest re-enables this explicitly for its
    // own dedicated enrichment test.
    @Value("${enrichment.enabled:true}") boolean enrichmentEnabled;

    /**
     * Most authors write in exactly one language - backfills
     * Author.nativeLanguage from whichever opus's own detected
     * TeiFile.language happens to be on hand (AdminService, right after
     * import), since nothing else detects this independently. Cheap and
     * synchronous (unlike the enrichment calls below), and unconditional -
     * runs even if this author was already enriched under the old,
     * language-less behavior.
     */
    public void backfillNativeLanguageIfMissing(Author author, Languages language) {
        if (author.getNativeLanguage() != null || language == null) return;
        this.authorRepository.findById(author.getId()).ifPresent(a -> {
            if (a.getNativeLanguage() != null) return;
            a.setNativeLanguage(language);
            this.authorRepository.save(a);
        });
    }

    public void enrichAuthorAsync(Author author, List<String> workTitles, Languages language) {
        if (!this.enrichmentEnabled || author.getBio() != null) return;
        final Long authorId = author.getId();
        final String query = author.getVisualName()
                + (workTitles.isEmpty() ? "" : ", author of " + String.join(", ", workTitles));
        final Languages searchLanguage = author.getNativeLanguage() != null ? author.getNativeLanguage() : language;

        Thread.ofVirtual().name("ai-enrich-author-" + author.getStrId()).start(() -> {
            try {
                final SourceMaterial material = findSourceMaterial(query, searchLanguage);
                if (material == null) {
                    log.info("No usable search material found to enrich author bio for '{}'", query);
                    return;
                }
                final String bio = summarize(material.text(),
                        "Write a concise two-paragraph biography of " + author.getVisualName()
                                + " based only on the material below. Do not invent facts that aren't in it.");
                if (bio == null) return;

                this.authorRepository.findById(authorId).ifPresent(a -> {
                    a.setBio(bio);
                    a.setBioSourceUrl(material.url());
                    this.authorRepository.save(a);
                });
                log.info("Enriched author bio for '{}' from {}", author.getVisualName(), material.url());
            } catch (Exception e) {
                log.warn("Author bio enrichment failed for '{}': {}", query, e.getMessage());
            }
        });
    }

    public void enrichOpusAsync(TeiDiv opus) {
        if (!this.enrichmentEnabled || opus.getSummary() != null) return;
        final Long opusId = opus.getId();
        final String title = opus.getHead();
        if (title == null || title.isBlank()) return;
        final String authorName = opus.getTeiFile().getAuthors().stream()
                .findFirst().map(Author::getVisualName).orElse(null);
        final String query = title + (authorName != null ? " by " + authorName : "");
        final Languages language = opus.getTeiFile().getLanguage();

        Thread.ofVirtual().name("ai-enrich-opus-" + opusId).start(() -> {
            try {
                final SourceMaterial material = findSourceMaterial(query, language);
                if (material == null) {
                    log.info("No usable search material found to enrich opus summary for '{}'", query);
                    return;
                }
                final String summary = summarize(material.text(),
                        "Write a concise two-paragraph summary of the work \"" + title
                                + "\" based only on the material below. Do not invent facts that aren't in it.");
                if (summary == null) return;

                this.teiDivRepository.findById(opusId).ifPresent(o -> {
                    o.setSummary(summary);
                    o.setSummarySourceUrl(material.url());
                    this.teiDivRepository.save(o);
                });
                log.info("Enriched opus summary for '{}' from {}", title, material.url());
            } catch (Exception e) {
                log.warn("Opus summary enrichment failed for '{}': {}", query, e.getMessage());
            }
        });
    }

    private record SourceMaterial(String text, String url) {}

    /**
     * Searches in the author's/opus's own native language when known - a
     * native-language query finds much better source material than an
     * English one for anyone not already writing in English (see the
     * Skala Eresou/Eminescu Greek-vs-English search comparison this is
     * based on). Prefers a trusted-domain hit (Wikipedia gets its own
     * clean REST extract, in English specifically when available - see
     * tryWikipediaExtract; Britannica falls back to SearXNG's own
     * snippet, no dedicated API for it); if none of the results are from
     * a trusted domain, synthesizes from the top 3 generic results' own
     * snippets instead - some material beats none, just held to a lower
     * bar.
     */
    private SourceMaterial findSourceMaterial(String query, Languages language) {
        final List<Map<String, Object>> results = searxngSearch(query, language);
        if (results.isEmpty()) return null;

        for (Map<String, Object> result : results) {
            final String url = String.valueOf(result.get("url"));
            if (isTrustedDomain(url)) {
                final String wikipediaExtract = tryWikipediaExtract(url);
                if (wikipediaExtract != null) return new SourceMaterial(wikipediaExtract, url);
                final String content = String.valueOf(result.getOrDefault("content", ""));
                if (!content.isBlank()) return new SourceMaterial(content, url);
            }
        }

        final String combined = results.stream()
                .limit(3)
                .map(r -> String.valueOf(r.getOrDefault("content", "")))
                .filter(c -> !c.isBlank())
                .collect(Collectors.joining("\n\n"));
        if (combined.isBlank()) return null;
        return new SourceMaterial(combined, String.valueOf(results.get(0).get("url")));
    }

    private boolean isTrustedDomain(String url) {
        return TRUSTED_DOMAINS.stream().anyMatch(url::contains);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> searxngSearch(String query, Languages language) {
        final UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(String.format("http://%s:%d/search", this.searxngHost, this.searxngPort))
                .queryParam("q", query)
                .queryParam("format", "json");
        if (language != null) builder.queryParam("language", language.getISO639_1Code());
        final Map<String, Object> response = getWithUserAgent(builder.build().toUri(), Map.class);
        if (response == null) return List.of();
        final Object results = response.get("results");
        return results instanceof List ? (List<Map<String, Object>>) results : List.of();
    }

    private <T> T getWithUserAgent(URI uri, Class<T> responseType) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        return this.restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
    }

    /**
     * Wikipedia's own REST summary API (docs: en.wikipedia.org/api/rest_v1/) -
     * a pre-written, clean plain-text "extract" for the article, no HTML
     * scraping needed. English is our first language (per the user - full
     * i18n of the stored text is a later concern), so this always tries
     * en.wikipedia.org first using the SAME article title the native-
     * language search found - works well for proper nouns like author
     * names, which are usually spelled identically across language
     * editions, and simply falls back to the native-language extract
     * when that guess doesn't resolve (a translated book title, say).
     * Returns null (falls through to the generic snippet path) for
     * anything that isn't actually a /wiki/Article_Name URL, or if
     * neither API call succeeds.
     */
    @SuppressWarnings("unchecked")
    private String tryWikipediaExtract(String url) {
        final Matcher m = WIKIPEDIA_URL.matcher(url);
        if (!m.find()) return null;
        final String nativeLang = m.group(1);
        final String title = URLDecoder.decode(m.group(2), StandardCharsets.UTF_8);

        // A colon in the title means this is a namespace page (File:,
        // Category:, Template:, or their many localized equivalents like
        // "Fișier:"/"Categorie:" in Romanian), not a real article - not
        // useful material for a bio/summary, and SearXNG's own ranking
        // surfaces these often enough (a category or file page mentioning
        // the query) that this is worth filtering rather than ignoring.
        // Real article titles essentially never contain a literal colon.
        if (title.contains(":")) return null;

        if (!"en".equals(nativeLang)) {
            final String englishExtract = wikipediaSummary("en", title);
            if (englishExtract != null) return englishExtract;
        }
        return wikipediaSummary(nativeLang, title);
    }

    @SuppressWarnings("unchecked")
    private String wikipediaSummary(String lang, String title) {
        try {
            final URI summaryUri = UriComponentsBuilder
                    .fromUriString(String.format("https://%s.wikipedia.org/api/rest_v1/page/summary/", lang))
                    .path(title)
                    .build(false).toUri();
            final Map<String, Object> response = getWithUserAgent(summaryUri, Map.class);
            final Object extract = response == null ? null : response.get("extract");
            return extract instanceof String && !((String) extract).isBlank() ? (String) extract : null;
        } catch (Exception e) {
            log.info("No {}.wikipedia.org summary for '{}': {}", lang, title, e.getMessage());
            return null;
        }
    }

    // Every enrichAuthorAsync/enrichOpusAsync call runs on its own virtual
    // thread, so a single big import (dozens of opera at once) would
    // otherwise fire that many concurrent Ollama generate requests -
    // this shared Ollama instance also serves live vector-search
    // embeddings, and a pile of simultaneous generate calls is exactly
    // the kind of GPU contention that's already a known problem here
    // (see the int-greg Ollama load concern). One at a time keeps this
    // enrichment work from ever being the thing that makes Ollama
    // unusably slow for everything else - it's already best-effort
    // background work, queueing is free.
    private final java.util.concurrent.Semaphore ollamaGate = new java.util.concurrent.Semaphore(1);

    @SuppressWarnings("unchecked")
    private String summarize(String material, String instruction) throws InterruptedException {
        this.ollamaGate.acquire();
        try {
            // Background enrichment only ever gets ONE shot per author/opus
            // (see AdminService's null-gating) - bailing out the instant
            // Ollama is flagged unavailable would permanently skip that
            // author/opus's enrichment for a merely transient outage. This
            // waits out the tracker's own remaining cooldown instead (a
            // virtual thread, already serialized by ollamaGate, so idle
            // waiting here is free) - bounded by that same cooldown, so it
            // never waits past the point the tracker would auto-reset
            // anyway, matching "wait before the 15/20 min interval
            // elapses, not indefinitely beyond it."
            if (!this.ollamaHealthTracker.isAvailable()) {
                final Duration wait = this.ollamaHealthTracker.remainingCooldown();
                if (!wait.isZero()) {
                    log.info("Ollama currently marked unavailable - waiting up to {} for it to recover before giving up on this enrichment.", wait);
                    Thread.sleep(wait.toMillis());
                }
                if (!this.ollamaHealthTracker.isAvailable()) return null;
            }

            final URI uri = URI.create(String.format("http://%s:%d/api/generate", this.ollamaHost, this.ollamaPort));
            final Map<String, Object> request = Map.of(
                    "model", this.ollamaModel,
                    "prompt", instruction + "\n\nMaterial:\n" + material,
                    "stream", false
            );
            final Map<String, Object> response;
            try {
                response = this.ollamaRestTemplate.postForObject(uri, request, Map.class);
            } catch (Exception e) {
                this.ollamaHealthTracker.markUnavailable();
                throw e;
            }
            this.ollamaHealthTracker.markAvailable();
            final Object text = response == null ? null : response.get("response");
            return text instanceof String && !((String) text).isBlank() ? ((String) text).trim() : null;
        } finally {
            this.ollamaGate.release();
        }
    }
}
