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
 * Best-effort background enrichment: a short author bio and opus summary,
 * taken directly from real-world search material via SearXNG (self-hosted
 * metasearch, docker/searxng in the scripts repo) - no LLM rewrite. A
 * Wikipedia REST summary extract is already clean, condensed prose (see
 * findSourceMaterial); the generic-snippet fallback is rougher (several
 * unrelated sites' blurbs concatenated) but still usable as-is, truncated
 * to a reasonable length - accepted as a fair tradeoff for never touching
 * Ollama on this path, since these fire once per author AND once per opus
 * (dozens of calls for one big multi-work TEI file).
 *
 * Ollama is used for exactly one thing here: extracting a small structured
 * JSON of author facts (birthDate, deathDate, birthPlace, etc.) that plain
 * text truncation can't produce - see enrichAuthorFactsAsync. That's
 * author-only (never per-opus) and genuinely rare in volume, unlike the
 * old "summarize every opus via Ollama" design this replaced.
 *
 * Every call here happens on its own virtual thread, fire-and-forget from
 * AdminService right after an opus/author is (re)imported - never blocks
 * the import itself, and any failure (SearXNG/Ollama unreachable, no
 * usable search results, whatever) is caught and logged, never thrown
 * back at the caller. Runs at most once per author/opus: both entities
 * only ever get enriched while their bio/summary is still null (see
 * AdminService), so this never re-spends work on every reimport, and a
 * manual edit to either field is never silently overwritten later.
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
                final String bio = truncateMaterial(material.text());
                final AuthorFacts facts = extractAuthorFacts(material.text(), author.getVisualName());

                this.authorRepository.findById(authorId).ifPresent(a -> {
                    a.setBio(bio);
                    a.setBioSourceUrl(material.url());
                    applyAuthorFacts(a, facts);
                    this.authorRepository.save(a);
                });
                log.info("Enriched author bio for '{}' from {}", author.getVisualName(), material.url());
            } catch (Exception e) {
                log.warn("Author bio enrichment failed for '{}': {}", query, e.getMessage());
            }
        });
    }

    private record AuthorFacts(String firstName, String lastName, String birthDate, String deathDate,
                                String nativeLanguage, String writingLanguage, String birthPlace, String country) {}

    /**
     * The only Ollama call left in this class - author-only (never
     * per-opus), so nowhere near the volume the old "summarize everything"
     * design produced. Best-effort like everything else here: any failure
     * (Ollama unreachable, bad JSON, whatever) returns null rather than
     * taking the bio - which doesn't need Ollama at all anymore - down
     * with it.
     */
    private AuthorFacts extractAuthorFacts(String material, String authorVisualName) throws InterruptedException {
        final String instruction = "Extract facts about " + authorVisualName + " from the material below."
                + " Respond with ONLY a single-line JSON object, no other text, with these exact keys:"
                + " firstName, lastName, birthDate, deathDate, nativeLanguage, writingLanguage, birthPlace,"
                + " country. Use a JSON null for any field you cannot determine from the material - do not guess.";
        final String raw = callOllama(instruction, material);
        log.info("extractAuthorFacts raw Ollama response for '{}': {}", authorVisualName, raw);
        return raw == null ? null : parseAuthorFacts(raw);
    }

    // Best-effort: a model that wraps its JSON in a markdown code fence
    // despite the "ONLY a JSON object" instruction (a common LLM quirk),
    // or produces invalid JSON outright, must never throw - just no facts
    // get applied.
    private AuthorFacts parseAuthorFacts(String raw) {
        final String json = stripCodeFence(raw.trim());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(json, AuthorFacts.class);
        } catch (Exception e) {
            log.info("Couldn't parse the author-facts JSON Ollama returned ({}): {}", e.getMessage(), json);
            return null;
        }
    }

    private static String stripCodeFence(String s) {
        if (!s.startsWith("```")) return s;
        final int firstNewline = s.indexOf('\n');
        final int lastFence = s.lastIndexOf("```");
        return firstNewline > 0 && lastFence > firstNewline ? s.substring(firstNewline + 1, lastFence).trim() : s;
    }

    // Only ever fills in a field that's still blank - existing structured
    // data (firstName/lastName from the TEI file, nativeLanguage from
    // backfillNativeLanguageIfMissing) is more reliable than an LLM's
    // guess at the same thing, so this never overwrites it. birthDate/
    // deathDate/birthPlace have no other source, so this is the only way
    // they ever get set.
    private static void applyAuthorFacts(Author a, AuthorFacts facts) {
        if (facts == null) return;
        if (isBlank(a.getFirstName()) && !isBlank(facts.firstName())) a.setFirstName(facts.firstName());
        if (isBlank(a.getLastName()) && !isBlank(facts.lastName())) a.setLastName(facts.lastName());
        if (isBlank(a.getBirthDate()) && !isBlank(facts.birthDate())) a.setBirthDate(facts.birthDate());
        if (isBlank(a.getDeathDate()) && !isBlank(facts.deathDate())) a.setDeathDate(facts.deathDate());
        if (isBlank(a.getBirthPlace()) && !isBlank(facts.birthPlace())) a.setBirthPlace(facts.birthPlace());
        if (isBlank(a.getCountry()) && !isBlank(facts.country())) a.setCountry(facts.country());
        if (a.getNativeLanguage() == null) parseLanguage(facts.nativeLanguage()).ifPresent(a::setNativeLanguage);
        if (a.getWritingLanguage() == null) parseLanguage(facts.writingLanguage()).ifPresent(a::setWritingLanguage);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // Not one of our ISO-639-1 enum codes (a full language name, an
    // unsupported language, whatever) -> empty, rather than guessing
    // further or throwing.
    private static java.util.Optional<Languages> parseLanguage(String code) {
        if (isBlank(code)) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(Languages.valueOf(code.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
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

        // No Ollama involved at all here - see the class doc comment.
        // findSourceMaterial's own text (a Wikipedia extract, or the
        // generic-snippet fallback), truncated, IS the summary.
        Thread.ofVirtual().name("ai-enrich-opus-" + opusId).start(() -> {
            try {
                final SourceMaterial material = findSourceMaterial(query, language);
                if (material == null) {
                    log.info("No usable search material found to enrich opus summary for '{}'", query);
                    return;
                }
                final String summary = truncateMaterial(material.text());

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

    // Author-only now (opus summaries no longer call Ollama at all - see
    // the class doc comment), so nowhere near the volume that justified
    // this originally, but still real: a big author-heavy import could
    // still fire several concurrent extractAuthorFacts calls, and this
    // shared Ollama instance also serves live vector-search embeddings.
    // One at a time keeps this from ever being the thing that makes
    // Ollama unusably slow for everything else - it's already best-effort
    // background work, queueing is free.
    private final java.util.concurrent.Semaphore ollamaGate = new java.util.concurrent.Semaphore(1);

    // Roughly 3 short paragraphs worth of source text - plenty for a
    // stored bio/summary, and (for the author-facts call) short enough
    // that it doesn't sit at the front of the input context for ages
    // before generation even starts.
    private static final int MAX_MATERIAL_CHARS = 1200;

    @SuppressWarnings("unchecked")
    private String callOllama(String instruction, String material) throws InterruptedException {
        this.ollamaGate.acquire();
        try {
            // Background enrichment only ever gets ONE shot per author
            // (see AdminService's null-gating on bio) - bailing out the
            // instant Ollama is flagged unavailable would permanently
            // skip that author's facts for a merely transient outage.
            // This waits out the tracker's own remaining cooldown instead
            // (a virtual thread, already serialized by ollamaGate, so
            // idle waiting here is free) - bounded by that same cooldown,
            // so it never waits past the point the tracker would
            // auto-reset anyway, matching "wait before the 15/20 min
            // interval elapses, not indefinitely beyond it."
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
                    "prompt", instruction + "\n\nMaterial:\n" + truncateMaterial(material),
                    "stream", false,
                    // enrichment.ollama.model (qwen3.5:4b) is a reasoning
                    // model - by default it spends its whole num_predict
                    // budget on a separate "thinking" field before ever
                    // starting the actual "response" (confirmed directly
                    // against the real server: response came back "" with
                    // done_reason "length" and 200 tokens of thinking that
                    // hadn't even finished one bullet point). This task is
                    // plain structured extraction, not something that
                    // needs deep reasoning - skip thinking entirely rather
                    // than just raising num_predict to cover it.
                    "think", false,
                    // A compact JSON object needs far fewer tokens than
                    // the old "prose paragraph + trailing JSON" combined
                    // response did.
                    "options", Map.of("num_predict", 200)
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

    // Cuts at the last sentence boundary (. ! ?) at or before the limit,
    // rather than mid-sentence, when one exists reasonably close to it -
    // a clean-ish first few paragraphs, not a word chopped in half.
    private static String truncateMaterial(String material) {
        if (material.length() <= MAX_MATERIAL_CHARS) return material;
        final String window = material.substring(0, MAX_MATERIAL_CHARS);
        final int lastSentenceEnd = Math.max(window.lastIndexOf('.'),
                Math.max(window.lastIndexOf('!'), window.lastIndexOf('?')));
        return lastSentenceEnd > MAX_MATERIAL_CHARS / 2 ? window.substring(0, lastSentenceEnd + 1) : window;
    }
}
