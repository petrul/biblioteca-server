package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ro.editii.scriptorium.dao.AuthorMediaAssociationRepository;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.DivMediaAssociationRepository;
import ro.editii.scriptorium.dao.MediaRefRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dto.EnrichmentUpdateDto;
import ro.editii.scriptorium.media.AuthorMediaAssociation;
import ro.editii.scriptorium.media.DivMediaAssociation;
import ro.editii.scriptorium.media.MediaRef;
import ro.editii.scriptorium.model.Languages;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The narrow persistence boundary for the NestJS enrichment worker (see
 * biblioteca-nestjs's EnrichmentService): the worker owns every external
 * call (Wikipedia's REST summary API, Wikidata facts) and POSTs the
 * result here; this server only writes it to the DB. Idempotent in the
 * same best-effort spirit as the retired in-process enrichment: existing
 * bio/summary text is never overwritten and image associations are
 * never duplicated, so a crashed-and-rerun sweep is always harmless.
 *
 * Nothing here can touch the vector collection - embeddings are precious
 * and are only ever dropped through the manual vector-store operations.
 */
@RestController
@RequestMapping("/api/internal/enrichment")
@RequiredArgsConstructor
public class EnrichmentRestController {
    private static final String ENRICHMENT_ROLE = "enrichment";
    private static final int MAX_ENRICHMENT_IMAGES = 3;

    private final AuthorRepository authors;
    private final TeiDivRepository divs;
    private final MediaRefRepository mediaRefs;
    private final AuthorMediaAssociationRepository authorMedia;
    private final DivMediaAssociationRepository divMedia;

    @PostMapping
    public ResponseEntity<?> update(@RequestBody EnrichmentUpdateDto update) {
        if (update.getAuthorStrId() != null) {
            var author = authors.getByStrId(update.getAuthorStrId());
            if (author.isEmpty()) return ResponseEntity.notFound().build();
            var a = author.get();
            if (blank(a.getBio()) && !blank(update.getBio())) { a.setBio(update.getBio()); a.setBioSourceUrl(update.getBioSourceUrl()); }
            if (blank(a.getBirthDate())) a.setBirthDate(update.getBirthDate());
            if (blank(a.getDeathDate())) a.setDeathDate(update.getDeathDate());
            if (blank(a.getBirthPlace())) a.setBirthPlace(update.getBirthPlace());
            if (blank(a.getCountry())) a.setCountry(update.getCountry());
            if (a.getWritingLanguage() == null && !blank(update.getWritingLanguage())) {
                try { a.setWritingLanguage(Languages.valueOf(update.getWritingLanguage().toUpperCase())); } catch (IllegalArgumentException ignored) { }
            }
            authors.save(a);
            persistAuthorImages(update.getAuthorStrId(), update.getImageUrls());
        }
        if (update.getOpusId() != null) {
            var opus = divs.findById(update.getOpusId());
            if (opus.isEmpty()) return ResponseEntity.notFound().build();
            var o = opus.get();
            if (blank(o.getSummary()) && !blank(update.getSummary())) { o.setSummary(update.getSummary()); o.setSummarySourceUrl(update.getSummarySourceUrl()); }
            divs.save(o);
            persistDivImages(o.getCompletePath(), update.getImageUrls());
        }
        return ResponseEntity.ok(Map.of("updated", true));
    }

    /**
     * Image associations, same semantics as the retired Java enrichment:
     * if any enrichment image is already associated, leave them as they
     * are; otherwise add only URLs not already associated. No image bytes
     * are downloaded or stored, and the same MediaRef is shared between
     * authors/opera that both point at it.
     */
    private void persistAuthorImages(String authorPath, List<String> imageUrls) {
        if (authorMedia.existsByAuthorPathAndMediaRefRole(authorPath, ENRICHMENT_ROLE)) return;
        for (String url : imageUrls(imageUrls)) {
            if (authorMedia.existsByAuthorPathAndMediaRefUrl(authorPath, url)) continue;
            authorMedia.save(new AuthorMediaAssociation(null, authorPath, saveImageRef(url)));
        }
    }

    private void persistDivImages(String divPath, List<String> imageUrls) {
        if (divMedia.existsByDivPathAndMediaRefRole(divPath, ENRICHMENT_ROLE)) return;
        for (String url : imageUrls(imageUrls)) {
            if (divMedia.existsByDivPathAndMediaRefUrl(divPath, url)) continue;
            divMedia.save(new DivMediaAssociation(null, divPath, saveImageRef(url)));
        }
    }

    private MediaRef saveImageRef(String url) {
        return this.mediaRefs.findById(url).orElseGet(() ->
                this.mediaRefs.save(MediaRef.builder().url(url)
                        .contentType(contentType(url)).role(ENRICHMENT_ROLE).build()));
    }

    private static List<String> imageUrls(List<String> imageUrls) {
        if (imageUrls == null) return List.of();
        return imageUrls.stream()
                .filter(u -> u != null && (u.startsWith("https://") || u.startsWith("http://")))
                .limit(MAX_ENRICHMENT_IMAGES)
                .toList();
    }

    private static String contentType(String url) {
        final String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
