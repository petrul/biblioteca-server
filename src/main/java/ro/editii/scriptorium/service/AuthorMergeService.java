package ro.editii.scriptorium.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ro.editii.scriptorium.Globals;
import ro.editii.scriptorium.dao.AuthorMediaAssociationRepository;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.DivMediaAssociationRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.media.AuthorMediaAssociation;
import ro.editii.scriptorium.media.DivMediaAssociation;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiFile;
import ro.editii.scriptorium.tei.CandidateUrlFragmGeneratorForTeiDivHead;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repairs author rows split by name variants: the corpus spells the same
 * person differently across TEI headers ("Alarcón, Pedro Antonio de" vs
 * "Alarcon,Pedro Antonio de"), and the exact-string identity on
 * originalNameInTeiFile used to give each spelling its own Author row,
 * slicing that person's works between them. TeifileParser now reuses an
 * identity-key match before creating a new row (Author.nameIdentityKey),
 * which stops NEW splits; this service repairs the EXISTING ones.
 *
 * The merge re-points everything that references the duplicate's strId or
 * its opera paths to the canonical - files, media associations, url
 * fragments - and then deletes the empty duplicate row. Enrichment fields
 * (bio, dates, places) move fill-only: canonical keeps what it already
 * has. Lucene documents and stored vectors for the re-pathed opera are
 * deliberately NOT touched here - same "search data is precious" policy
 * as pruneRemovedTeis: old paths simply 404 until the next rebuild.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class AuthorMergeService {

    final AuthorRepository authorRepository;
    final TeiFileRepository teiFileRepository;
    final TeiDivRepository teiDivRepository;
    final AuthorMediaAssociationRepository authorMedia;
    final DivMediaAssociationRepository divMedia;

    /** One identity-key group holding more than one author row. */
    public record NameVariantGroup(String identityKey, List<Author> authors) {}

    /**
     * All author rows that share an identity key with at least one other
     * row - the same person under two or more spellings. Review with GET
     * /api/admin/authors/name-variants before bulk-merging.
     */
    public List<NameVariantGroup> nameVariants() {
        final Map<String, List<Author>> byKey = new LinkedHashMap<>();
        for (final Author author : this.authorRepository.findAll()) {
            byKey.computeIfAbsent(Author.nameIdentityKey(author.getOriginalNameInTeiFile()),
                    k -> new ArrayList<>()).add(author);
        }
        return byKey.values().stream()
                .filter(group -> group.size() > 1)
                .map(group -> new NameVariantGroup(
                        Author.nameIdentityKey(group.get(0).getOriginalNameInTeiFile()), group))
                .toList();
    }

    /**
     * Canonical pick for a variant group: the row holding the most
     * tei_files (the person's main identity in the corpus), tie-broken by
     * the lower id (the row created first). Deterministic, so a repeated
     * bulk merge never flip-flops between two candidates.
     */
    private Author pickCanonical(final List<Author> group) {
        return group.stream()
                .min(Comparator
                        .comparing((final Author a) -> this.authorRepository.getTeiFiles(a.getId()).size()).reversed()
                        .thenComparing(Author::getId))
                .orElseThrow();
    }

    /**
     * Merges every name-variant group the same way as merge(): the
     * canonical of each group is pickCanonical's choice. Returns one
     * summary per merged group. Safe to re-run - a repaired group no
     * longer shows up in nameVariants().
     */
    @Transactional
    public List<Map<String, Object>> mergeAllNameVariants() {
        final List<Map<String, Object>> merges = new ArrayList<>();
        for (final NameVariantGroup group : this.nameVariants()) {
            final Author canonical = this.pickCanonical(group.authors());
            for (final Author duplicate : group.authors()) {
                if (duplicate.getId() == canonical.getId()) continue;
                final Map<String, Object> summary = this.merge(canonical.getStrId(), duplicate.getStrId());
                summary.put("identityKey", group.identityKey());
                merges.add(summary);
            }
        }
        return merges;
    }

    /**
     * Folds the duplicate author row into the canonical one:
     * - every tei_file of the duplicate is re-attached to the canonical
     *   (each file has exactly one author by parser construction);
     * - any urlFragment of the merged opera that would collide with a
     *   canonical-native work (another edition may already use the same
     *   title) is regenerated to a unique one, so completePaths stay
     *   unambiguous;
     * - media associations re-point to the canonical strId and the new
     *   div paths, dropping ones the canonical already holds (the
     *   (path, media_ref) pair is unique);
     * - enrichment fields move fill-only;
     * - the now-empty duplicate row is deleted.
     */
    @Transactional
    public Map<String, Object> merge(final String canonicalStrId, final String duplicateStrId) {
        if (canonicalStrId == null || duplicateStrId == null || canonicalStrId.equals(duplicateStrId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "canonical and duplicate strIds must be two different authors");

        final Author canonical = this.authorRepository.getByStrId(canonicalStrId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no author " + canonicalStrId));
        final Author duplicate = this.authorRepository.getByStrId(duplicateStrId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no author " + duplicateStrId));

        // Snapshot BEFORE re-attaching: the fragments the canonical's own
        // opera already occupy, and the duplicate's opera (the incoming
        // ones completePath-visible only after the files move).
        final Set<String> reserved = new HashSet<>();
        for (final TeiDiv opus : this.teiDivRepository.findOperaForAuthorStrId(canonicalStrId))
            reserved.add(opus.getUrlFragment());
        final List<TeiDiv> incoming = this.teiDivRepository.findOperaForAuthorStrId(duplicateStrId);

        // 1. re-attach every file to the canonical author
        final List<TeiFile> duplicateFiles = this.authorRepository.getTeiFiles(duplicate.getId());
        for (final TeiFile file : duplicateFiles) {
            file.setAuthors(new ArrayList<>(List.of(canonical)));
        }
        this.teiFileRepository.saveAllAndFlush(duplicateFiles);

        // 2. resolve urlFragment collisions under the merged identity
        final Map<String, String> fragmentRenames = new HashMap<>(); // old fragment -> new
        final Set<String> taken = new HashSet<>(reserved);
        for (final TeiDiv opus : incoming) {
            if (taken.add(opus.getUrlFragment())) continue;
            final String fresh = this.firstFreeFragment(opus.getHead(), taken);
            fragmentRenames.put(opus.getUrlFragment(), fresh);
            opus.setUrlFragment(fresh);
            this.teiDivRepository.save(opus);
        }

        // 3. re-point the div-media associations to the new paths
        //    (enrichment only ever associates root-opus paths - see
        //    EnrichmentRestController.persistDivImages)
        int remappedDivMedia = 0, droppedDivMedia = 0;
        final String oldPathPrefix = duplicateStrId + "/";
        for (final DivMediaAssociation association : this.divMedia.findAllByDivPathStartingWith(oldPathPrefix)) {
            final String fragment = association.getDivPath().substring(oldPathPrefix.length());
            final String newPath = canonicalStrId + "/" + fragmentRenames.getOrDefault(fragment, fragment);
            if (this.divMedia.existsByDivPathAndMediaRefUrl(newPath, association.getMediaRef().getUrl())) {
                this.divMedia.delete(association);
                droppedDivMedia++;
            } else {
                association.setDivPath(newPath);
                this.divMedia.save(association);
                remappedDivMedia++;
            }
        }

        // 4. author-media associations follow the strId
        int remappedAuthorMedia = 0, droppedAuthorMedia = 0;
        for (final AuthorMediaAssociation association : this.authorMedia.findAllByAuthorPath(duplicateStrId)) {
            if (this.authorMedia.existsByAuthorPathAndMediaRefUrl(canonicalStrId, association.getMediaRef().getUrl())) {
                this.authorMedia.delete(association);
                droppedAuthorMedia++;
            } else {
                association.setAuthorPath(canonicalStrId);
                this.authorMedia.save(association);
                remappedAuthorMedia++;
            }
        }

        // 5. enrichment, fill-only
        int copied = 0;
        if (isBlank(canonical.getBio()) && !isBlank(duplicate.getBio())) { canonical.setBio(duplicate.getBio()); copied++; }
        if (isBlank(canonical.getBioSourceUrl()) && !isBlank(duplicate.getBioSourceUrl())) { canonical.setBioSourceUrl(duplicate.getBioSourceUrl()); copied++; }
        if (isBlank(canonical.getBirthDate()) && !isBlank(duplicate.getBirthDate())) { canonical.setBirthDate(duplicate.getBirthDate()); copied++; }
        if (isBlank(canonical.getDeathDate()) && !isBlank(duplicate.getDeathDate())) { canonical.setDeathDate(duplicate.getDeathDate()); copied++; }
        if (isBlank(canonical.getBirthPlace()) && !isBlank(duplicate.getBirthPlace())) { canonical.setBirthPlace(duplicate.getBirthPlace()); copied++; }
        if (isBlank(canonical.getCountry()) && !isBlank(duplicate.getCountry())) { canonical.setCountry(duplicate.getCountry()); copied++; }
        if (canonical.getNativeLanguage() == null && duplicate.getNativeLanguage() != null) { canonical.setNativeLanguage(duplicate.getNativeLanguage()); copied++; }
        if (canonical.getWritingLanguage() == null && duplicate.getWritingLanguage() != null) { canonical.setWritingLanguage(duplicate.getWritingLanguage()); copied++; }

        // 6. the duplicate is now file-less; drop it
        this.authorRepository.delete(duplicate);

        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("canonical", canonicalStrId);
        summary.put("duplicateRemoved", duplicateStrId);
        summary.put("filesReattached", duplicateFiles.size());
        summary.put("fragmentsRenumbered", fragmentRenames.size());
        summary.put("divMediaRemapped", remappedDivMedia);
        summary.put("divMediaDropped", droppedDivMedia);
        summary.put("authorMediaRemapped", remappedAuthorMedia);
        summary.put("authorMediaDropped", droppedAuthorMedia);
        summary.put("enrichmentFieldsCopied", copied);
        log.info("merged author {} into {}: {} files, {} fragments renumbered, {} div media remapped, {} author media remapped",
                duplicateStrId, canonicalStrId, duplicateFiles.size(), fragmentRenames.size(),
                remappedDivMedia, remappedAuthorMedia);
        return summary;
    }

    private String firstFreeFragment(final String head, final Set<String> taken) {
        for (final String candidate : new CandidateUrlFragmGeneratorForTeiDivHead(head)) {
            if (!taken.contains(candidate)) return candidate;
        }
        // unreachable: the candidate generator is infinite by design
        throw new IllegalStateException("url fragment generator ran dry for head " + head);
    }

    /**
     * Sweeps author rows left with no attached tei_file - the exact
     * mirror of pruneOrphanedElems, for the author table. The per-file
     * orphan cleanup in TeiFileDbService.deleteTeiFile provably cannot
     * reach these rows: it only visits the authors of the file it
     * deletes, and a file-less author is attached to nothing, so until
     * this sweep existed it stayed forever (the 672 stale rows behind
     * the "936 authors but 22 works" sighting).
     *
     * Deleting the row also drops its author_media associations - they
     * key by the strId string, not an FK, and would dangle behind the
     * deleted row. Lost bios are re-derivable: the nestjs enrichment
     * worker re-fetches them (fill-only) once the author's files come
     * back through a reimport and a fresh row is created.
     *
     * Orphanhood is re-checked at deletion time, not trusted from the
     * listing: with more than one importer JVM sharing one DB (no
     * shared lock), a file may have re-attached the author between the
     * list query and the delete - the same staleness the whole prune
     * family guards against. Per-item isolation: one failing row never
     * aborts the rest of the sweep.
     */
    public int pruneOrphanedAuthors(final java.io.Writer logActivity) {
        try (Globals.ImportLock ignored = Globals.lockImports()) {
            final List<Author> orphans = this.authorRepository.findOrphanedAuthors();
            int pruned = 0;
            for (final Author orphan : orphans) {
                try {
                    if (!this.authorRepository.getTeiFiles(orphan.getId()).isEmpty()) {
                        log.info("skipping author {} - re-attached to a file since the sweep listed it", orphan.getStrId());
                        continue;
                    }
                    for (final AuthorMediaAssociation association : this.authorMedia.findAllByAuthorPath(orphan.getStrId()))
                        this.authorMedia.delete(association);
                    this.authorRepository.delete(orphan);
                    pruned++;
                    log.info("pruned orphaned author {} [{}] - no tei_file attached", orphan.getStrId(), orphan.getOriginalNameInTeiFile());
                    writeLn(logActivity, "pruned orphaned author " + orphan.getStrId());
                } catch (RuntimeException e) {
                    log.error("Failed to prune orphaned author {} - continuing with the rest (it will be retried next sweep)",
                            orphan.getStrId(), e);
                }
            }
            if (pruned > 0) {
                log.info("Orphan sweep: {} author rows removed", pruned);
                writeLn(logActivity, "orphan sweep: " + pruned + " author rows removed");
            }
            return pruned;
        }
    }

    private static void writeLn(final java.io.Writer writer, final String s) {
        try {
            writer.write(s + " <br/>\n");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isBlank(final String s) { return s == null || s.isBlank(); }
}
