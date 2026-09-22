package ro.editii.scriptorium.enrichment;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.AuthorMediaAssociationRepository;
import ro.editii.scriptorium.dao.DivMediaAssociationRepository;
import ro.editii.scriptorium.dao.MediaRefRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.media.AuthorMediaAssociation;
import ro.editii.scriptorium.media.DivMediaAssociation;
import ro.editii.scriptorium.media.MediaRef;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.Languages;
import ro.editii.scriptorium.model.TeiDiv;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * to a reasonable length - accepted as a fair tradeoff for avoiding a
 * generative-model dependency on this path
 * (dozens of calls for one big multi-work TEI file).
 *
 * Structured author facts come from the Wikidata entity linked by the
 * selected Wikipedia article. Representative author/work image URLs come
 * first from Wikipedia's lead image and then from carefully filtered
 * SearXNG image results. No image bytes are downloaded or stored.
 *
 * Every call here happens on its own virtual thread, fire-and-forget from
 * AdminService right after an opus/author is (re)imported - never blocks
 * the import itself, and any failure (SearXNG/Wikipedia unreachable, no
 * usable search results, whatever) is caught and logged, never thrown
 * back at the caller. Runs at most once per author/opus: both entities
 * existing bio/summary text is never overwritten; image associations are
 * also idempotent, so reimports only fill missing enrichment.
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
public class EnrichmentService {

    private static final List<String> TRUSTED_DOMAINS = List.of("wikipedia.org", "britannica.com");
    private static final String ENRICHMENT_ROLE = "enrichment";
    private static final int MAX_ENRICHMENT_IMAGES = 3;
    private static final Pattern WIKIPEDIA_URL = Pattern.compile("https?://([a-z-]+)\\.wikipedia\\.org/wiki/([^#?]+)");

    // Wikimedia's API etiquette REQUIRES a descriptive User-Agent on every
    // request (see https://w.wiki/4wJS) - without one, both the REST
    // summary API and, empirically, even a stock RestTemplate GET get a
    // flat 403 rather than a real response.
    private static final String USER_AGENT =
            "BibliotecaServer-Enrichment/1.0 (https://biblioteca.scriptorium.ro; self-hosted TEI library)";

    final RestTemplate restTemplate;
    final AuthorRepository authorRepository;
    final TeiDivRepository teiDivRepository;
    final MediaRefRepository mediaRefRepository;
    final AuthorMediaAssociationRepository authorMediaAssociationRepository;
    final DivMediaAssociationRepository divMediaAssociationRepository;

    @Value("${searxng.host}") String searxngHost;
    @Value("${searxng.port}") int searxngPort;
    // Off by default under the ci profile (application-ci.properties) -
    // every test class that imports the fixture corpus would otherwise
    // also fire real SearXNG/Wikipedia/Wikidata calls per author/opus, on
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
        if (!this.enrichmentEnabled) return;
        final Long authorId = author.getId();
        final String query = author.getVisualName()
                + (workTitles.isEmpty() ? "" : ", author of " + String.join(", ", workTitles));
        final Languages searchLanguage = author.getNativeLanguage() != null ? author.getNativeLanguage() : language;

        Thread.ofVirtual().name("enrich-author-" + author.getStrId()).start(() -> {
            try {
                final SourceMaterial material = findSourceMaterial(query, searchLanguage);
                if (material != null) {
                    this.authorRepository.findById(authorId).ifPresent(a -> {
                        if (a.getBio() == null) {
                            a.setBio(truncateMaterial(material.text()));
                            a.setBioSourceUrl(material.url());
                        }
                        applyWikidataFacts(a, material.wikibaseItem());
                        this.authorRepository.save(a);
                    });
                    log.info("Enriched author bio/facts for '{}' from {}", author.getVisualName(), material.url());
                }
                enrichAuthorImages(author.getStrId(), author.getVisualName(), workTitles,
                        searchLanguage, material == null ? null : material.imageUrl());
            } catch (Exception e) {
                log.warn("Author enrichment failed for '{}': {}", query, e.getMessage());
            }
        });
    }

    public void enrichOpusAsync(TeiDiv opus) {
        if (!this.enrichmentEnabled) return;
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
                if (material != null && opus.getSummary() == null) {
                    this.teiDivRepository.findById(opusId).ifPresent(o -> {
                        if (o.getSummary() == null) {
                            o.setSummary(truncateMaterial(material.text()));
                            o.setSummarySourceUrl(material.url());
                            this.teiDivRepository.save(o);
                        }
                    });
                    log.info("Enriched opus summary for '{}' from {}", title, material.url());
                }
                enrichDivImages(opus.getCompletePath(), title, authorName, language,
                        material == null ? null : material.imageUrl());
            } catch (Exception e) {
                log.warn("Opus enrichment failed for '{}': {}", query, e.getMessage());
            }
        });
    }

    private record SourceMaterial(String text, String url, String imageUrl, String wikibaseItem) {}

    /**
     * Searches in the author's/opus's own native language when known - a
     * native-language query finds much better source material than an
     * English one for anyone not already writing in English (see the
     * Skala Eresou/Eminescu Greek-vs-English search comparison this is
     * based on). Prefers a trusted-domain hit (Wikipedia gets its own
     * clean REST extract, in English specifically when available - see
     * tryWikipediaPage; Britannica falls back to SearXNG's own
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
                final WikipediaPage wikipediaPage = tryWikipediaPage(url);
                if (wikipediaPage != null) return new SourceMaterial(
                        wikipediaPage.extract(), url, wikipediaPage.imageUrl(), wikipediaPage.wikibaseItem());
                final String content = String.valueOf(result.getOrDefault("content", ""));
                if (!content.isBlank()) return new SourceMaterial(content, url, null, null);
            }
        }

        final String combined = results.stream()
                .limit(3)
                .map(r -> String.valueOf(r.getOrDefault("content", "")))
                .filter(c -> !c.isBlank())
                .collect(Collectors.joining("\n\n"));
        if (combined.isBlank()) return null;
        return new SourceMaterial(combined, String.valueOf(results.get(0).get("url")), null, null);
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
    private record WikipediaPage(String extract, String imageUrl, String wikibaseItem) {}

    private WikipediaPage tryWikipediaPage(String url) {
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
            final WikipediaPage englishPage = wikipediaSummary("en", title);
            if (englishPage != null) return englishPage;
        }
        return wikipediaSummary(nativeLang, title);
    }

    @SuppressWarnings("unchecked")
    private WikipediaPage wikipediaSummary(String lang, String title) {
        try {
            final URI summaryUri = UriComponentsBuilder
                    .fromUriString(String.format("https://%s.wikipedia.org/api/rest_v1/page/summary/", lang))
                    .path(title)
                    .build(false).toUri();
            final Map<String, Object> response = getWithUserAgent(summaryUri, Map.class);
            final Object extract = response == null ? null : response.get("extract");
            if (!(extract instanceof String) || ((String) extract).isBlank()) return null;
            final Map<String, Object> originalImage = response.get("originalimage") instanceof Map
                    ? (Map<String, Object>) response.get("originalimage") : Map.of();
            final Map<String, Object> thumbnail = response.get("thumbnail") instanceof Map
                    ? (Map<String, Object>) response.get("thumbnail") : Map.of();
            final String imageUrl = firstHttpUrl(originalImage.get("source"), thumbnail.get("source"));
            final Object wikibaseItem = response.get("wikibase_item");
            return new WikipediaPage((String) extract, imageUrl,
                    wikibaseItem instanceof String ? (String) wikibaseItem : null);
        } catch (Exception e) {
            log.info("No {}.wikipedia.org summary for '{}': {}", lang, title, e.getMessage());
            return null;
        }
    }

    private static final int MAX_MATERIAL_CHARS = 1200;

    private void applyWikidataFacts(Author author, String wikibaseItem) {
        if (wikibaseItem == null || wikibaseItem.isBlank()) return;
        try {
            final URI uri = URI.create("https://www.wikidata.org/wiki/Special:EntityData/" + wikibaseItem + ".json");
            final Map<String, Object> root = getWithUserAgent(uri, Map.class);
            final Map<String, Object> entities = asMap(root == null ? null : root.get("entities"));
            final Map<String, Object> entity = asMap(entities.get(wikibaseItem));
            final Map<String, Object> claims = asMap(entity.get("claims"));

            if (isBlank(author.getBirthDate())) author.setBirthDate(claimTime(claims, "P569"));
            if (isBlank(author.getDeathDate())) author.setDeathDate(claimTime(claims, "P570"));

            final String birthPlaceId = firstEntityId(claims, "P19");
            final String countryId = firstEntityId(claims, "P27");
            final List<String> languageIds = entityIds(claims, "P1412");
            final Set<String> labelIds = new LinkedHashSet<>();
            if (birthPlaceId != null) labelIds.add(birthPlaceId);
            if (countryId != null) labelIds.add(countryId);
            labelIds.addAll(languageIds);
            final Map<String, String> labels = wikidataLabels(labelIds);
            if (isBlank(author.getBirthPlace())) author.setBirthPlace(labels.get(birthPlaceId));
            if (isBlank(author.getCountry())) author.setCountry(labels.get(countryId));
            if (author.getWritingLanguage() == null) {
                languageIds.stream().map(this::wikidataLanguageCode).filter(s -> s != null)
                        .map(String::toUpperCase).map(this::parseLanguage).filter(l -> l != null)
                        .findFirst().ifPresent(author::setWritingLanguage);
            }
        } catch (Exception e) {
            log.info("Could not read Wikidata facts for {}: {}", wikibaseItem, e.getMessage());
        }
    }

    private void enrichAuthorImages(String authorPath, String name, List<String> works,
                                    Languages language, String wikipediaImage) {
        if (this.authorMediaAssociationRepository.existsByAuthorPathAndMediaRefRole(authorPath, ENRICHMENT_ROLE)) return;
        final String context = works.stream().filter(s -> s != null && !s.isBlank()).findFirst()
                .map(s -> " \"" + s + "\"").orElse("");
        final String query = "\"" + name + "\" writer author portrait" + context;
        persistAuthorImages(authorPath, imageCandidates(query, name, language, wikipediaImage));
    }

    private void enrichDivImages(String divPath, String title, String authorName,
                                 Languages language, String wikipediaImage) {
        if (this.divMediaAssociationRepository.existsByDivPathAndMediaRefRole(divPath, ENRICHMENT_ROLE)) return;
        final String identity = title + (authorName == null ? "" : " " + authorName);
        final String query = "\"" + title + "\"" + (authorName == null ? "" : " \"" + authorName + "\"")
                + " book cover title page illustration";
        persistDivImages(divPath, imageCandidates(query, identity, language, wikipediaImage));
    }

    private record ImageCandidate(String imageUrl, String pageUrl, Integer width, Integer height, int score) {}

    private List<ImageCandidate> imageCandidates(String query, String identity,
                                                  Languages language, String wikipediaImage) {
        final LinkedHashMap<String, ImageCandidate> candidates = new LinkedHashMap<>();
        if (isHttpUrl(wikipediaImage)) {
            candidates.put(wikipediaImage, new ImageCandidate(wikipediaImage, null, null, null, 1000));
        }
        final List<String> identityTokens = significantTokens(identity);
        for (Map<String, Object> result : searxngImageSearch(query, language)) {
            final String imageUrl = firstHttpUrl(result.get("img_src"), result.get("thumbnail_src"), result.get("thumbnail"));
            if (!usableImageUrl(imageUrl)) continue;
            final String pageUrl = firstHttpUrl(result.get("url"));
            final String haystack = normalize(String.valueOf(result.getOrDefault("title", "")) + " "
                    + String.valueOf(result.getOrDefault("content", "")) + " " + pageUrl + " " + imageUrl);
            final long matches = identityTokens.stream().filter(haystack::contains).count();
            if (matches < Math.min(2, identityTokens.size())) continue;
            final int trustedBonus = containsAny(pageUrl, "commons.wikimedia.org", "wikipedia.org")
                    || imageUrl.contains("wikimedia.org") ? 200 : 0;
            final int[] dimensions = parseResolution(result.get("resolution"));
            final int resolutionBonus = dimensions[0] >= 600 && dimensions[1] >= 600 ? 20 : 0;
            final ImageCandidate candidate = new ImageCandidate(imageUrl, pageUrl,
                    dimensions[0] > 0 ? dimensions[0] : null, dimensions[1] > 0 ? dimensions[1] : null,
                    trustedBonus + resolutionBonus + (int) matches * 10);
            candidates.merge(imageUrl, candidate, (a, b) -> a.score() >= b.score() ? a : b);
        }
        return candidates.values().stream().sorted(Comparator.comparingInt(ImageCandidate::score).reversed())
                .limit(MAX_ENRICHMENT_IMAGES).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> searxngImageSearch(String query, Languages language) {
        final UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(String.format("http://%s:%d/search", this.searxngHost, this.searxngPort))
                .queryParam("q", query).queryParam("categories", "images").queryParam("format", "json");
        if (language != null) builder.queryParam("language", language.getISO639_1Code());
        final Map<String, Object> response = getWithUserAgent(builder.build().toUri(), Map.class);
        return response != null && response.get("results") instanceof List
                ? (List<Map<String, Object>>) response.get("results") : List.of();
    }

    private void persistAuthorImages(String authorPath, List<ImageCandidate> images) {
        images.forEach(image -> {
            if (this.authorMediaAssociationRepository.existsByAuthorPathAndMediaRefUrl(authorPath, image.imageUrl())) return;
            final MediaRef media = saveImageRef(image);
            this.authorMediaAssociationRepository.save(new AuthorMediaAssociation(null, authorPath, media));
        });
    }

    private void persistDivImages(String divPath, List<ImageCandidate> images) {
        images.forEach(image -> {
            if (this.divMediaAssociationRepository.existsByDivPathAndMediaRefUrl(divPath, image.imageUrl())) return;
            final MediaRef media = saveImageRef(image);
            this.divMediaAssociationRepository.save(new DivMediaAssociation(null, divPath, media));
        });
    }

    private MediaRef saveImageRef(ImageCandidate image) {
        return this.mediaRefRepository.findById(image.imageUrl()).orElseGet(() ->
                this.mediaRefRepository.save(MediaRef.builder().url(image.imageUrl())
                        .contentType(contentType(image.imageUrl())).width(image.width()).height(image.height())
                        .role(ENRICHMENT_ROLE).build()));
    }

    private Map<String, String> wikidataLabels(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        final URI uri = UriComponentsBuilder.fromUriString("https://www.wikidata.org/w/api.php")
                .queryParam("action", "wbgetentities").queryParam("format", "json")
                .queryParam("props", "labels").queryParam("languages", "en|ro")
                .queryParam("ids", String.join("|", ids)).build().toUri();
        final Map<String, Object> response = getWithUserAgent(uri, Map.class);
        final Map<String, Object> entities = asMap(response == null ? null : response.get("entities"));
        final Map<String, String> result = new LinkedHashMap<>();
        ids.forEach(id -> {
            final Map<String, Object> labels = asMap(asMap(entities.get(id)).get("labels"));
            final Map<String, Object> label = labels.containsKey("en") ? asMap(labels.get("en")) : asMap(labels.get("ro"));
            if (label.get("value") instanceof String value) result.put(id, value);
        });
        return result;
    }

    private String wikidataLanguageCode(String id) {
        try {
            final URI uri = URI.create("https://www.wikidata.org/wiki/Special:EntityData/" + id + ".json");
            final Map<String, Object> root = getWithUserAgent(uri, Map.class);
            final Map<String, Object> entity = asMap(asMap(root.get("entities")).get(id));
            final Object value = firstClaimValue(asMap(entity.get("claims")), "P218");
            return value instanceof String ? (String) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Languages parseLanguage(String code) {
        try {
            return Languages.valueOf(code);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String claimTime(Map<String, Object> claims, String property) {
        final Object value = firstClaimValue(claims, property);
        if (!(value instanceof Map)) return null;
        final Object time = ((Map<?, ?>) value).get("time");
        final Object precisionValue = ((Map<?, ?>) value).get("precision");
        if (!(time instanceof String raw)) return null;
        final int precision = precisionValue instanceof Number ? ((Number) precisionValue).intValue() : 11;
        final String normalized = raw.replaceFirst("^\\+", "");
        final String date = normalized.substring(0, Math.min(10, normalized.length()));
        if (date.length() < 4) return null;
        if (precision <= 9) return date.substring(0, 4);
        if (precision == 10) return date.substring(0, Math.min(7, date.length()));
        return date;
    }

    private static String firstEntityId(Map<String, Object> claims, String property) {
        final Object value = firstClaimValue(claims, property);
        return value instanceof Map && ((Map<?, ?>) value).get("id") instanceof String
                ? (String) ((Map<?, ?>) value).get("id") : null;
    }

    private static List<String> entityIds(Map<String, Object> claims, String property) {
        final List<Object> values = claimValues(claims, property);
        return values.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(v -> v.get("id")).filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static Object firstClaimValue(Map<String, Object> claims, String property) {
        return claimValues(claims, property).stream().findFirst().orElse(null);
    }

    private static List<Object> claimValues(Map<String, Object> claims, String property) {
        final Object rawClaims = claims.get(property);
        if (!(rawClaims instanceof List<?> list)) return List.of();
        final List<Object> values = new ArrayList<>();
        for (Object claim : list) {
            final Map<String, Object> mainsnak = asMap(asMap(claim).get("mainsnak"));
            final Map<String, Object> datavalue = asMap(mainsnak.get("datavalue"));
            if (datavalue.containsKey("value")) values.add(datavalue.get("value"));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String firstHttpUrl(Object... values) {
        for (Object value : values) if (value instanceof String s && isHttpUrl(s)) return s;
        return null;
    }

    private static boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("https://") || value.startsWith("http://"));
    }

    private static boolean usableImageUrl(String value) {
        if (!isHttpUrl(value)) return false;
        final String lower = value.toLowerCase(Locale.ROOT);
        return !lower.contains("favicon") && !lower.contains("logo") && !lower.endsWith(".svg")
                && !lower.endsWith(".gif");
    }

    private static List<String> significantTokens(String value) {
        return List.of(normalize(value).split("\\s+")).stream()
                .filter(s -> s.length() >= 3).distinct().toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null) return false;
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static int[] parseResolution(Object resolution) {
        if (!(resolution instanceof String value)) return new int[]{0, 0};
        final Matcher matcher = Pattern.compile("(\\d+)\\s*[x×]\\s*(\\d+)").matcher(value);
        return matcher.find() ? new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))}
                : new int[]{0, 0};
    }

    private static String contentType(String url) {
        final String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
