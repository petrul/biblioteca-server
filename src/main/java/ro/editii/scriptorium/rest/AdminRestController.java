package ro.editii.scriptorium.rest;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.rest.core.annotation.RestResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ro.editii.scriptorium.VersionProperties;
import ro.editii.scriptorium.dao.DivMediaAssociationRepository;
import ro.editii.scriptorium.dao.MediaRefRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dto.TeiRepoDto;
import ro.editii.scriptorium.media.DivMediaAssociation;
import ro.editii.scriptorium.media.MediaRef;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.scheduled.NoWriter;
import ro.editii.scriptorium.service.AdminService;
import ro.editii.scriptorium.service.AuthorMergeService;
import ro.editii.scriptorium.tei.CombinedTeiRepo;
import ro.editii.scriptorium.tei.TeiRepo;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Hidden
public class AdminRestController {

    final TeiRepo teiRepo;
    final AdminService adminService;
    final AuthorMergeService authorMergeService;
    final Environment environment;
    final VersionProperties versionProperties;
    final TeiDivRepository teiDivRepository;
    final DivMediaAssociationRepository divMedia;
    final MediaRefRepository mediaRefs;

    // Distinct from EnrichmentRestController's "enrichment" role: a manual
    // admin attachment must never be skipped by the automated pipeline's
    // fill-only gate (existsByDivPathAndMediaRefRole(path, "enrichment")),
    // and vice versa - the two are deliberately independent pools so a
    // hand-picked cover candidate always lands regardless of what
    // enrichment has or hasn't found already.
    private static final String MANUAL_ROLE = "manual";

    /**
     * Attach an already-uploaded image (e.g. a JPG pushed to MinIO by hand)
     * to a work/page as a potential cover-art candidate - the admin-facing
     * counterpart to EnrichmentRestController's automated, fill-only write
     * path. Multiple images may be attached to the same div/opus; nothing
     * here caps or replaces earlier ones. biblioteca-nestjs's cover
     * ordering picks randomly among whatever a work has (see
     * CoverEnrichmentService/authorArts' sibling opus-art lookup) once
     * that lookup is extended to the "manual" role too.
     */
    @PostMapping("/divs/{id}/media")
    public ResponseEntity<?> attachDivMedia(@PathVariable long id, @RequestParam String url) {
        final TeiDiv div = this.teiDivRepository.findById(id).filter(TeiDiv.class::isInstance).map(TeiDiv.class::cast).orElse(null);
        if (div == null) return ResponseEntity.notFound().build();
        final String divPath = div.getCompletePath();
        if (this.divMedia.existsByDivPathAndMediaRefUrl(divPath, url))
            return ResponseEntity.ok(Map.of("divPath", divPath, "alreadyAssociated", true));
        final MediaRef ref = this.mediaRefs.findById(url).orElseGet(() ->
                this.mediaRefs.save(MediaRef.builder().url(url).contentType(contentType(url)).role(MANUAL_ROLE).build()));
        this.divMedia.save(new DivMediaAssociation(null, divPath, ref));
        return ResponseEntity.ok(Map.of("divPath", divPath, "url", url, "associated", true));
    }

    private static String contentType(String url) {
        final String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".webp")) return "image/webp";
        return "image/jpeg";
    }

    @GetMapping("/version")
    public @ResponseBody Map version() {
        final String appname = this.environment.getProperty("app.name");
        final String gitLatestCommit = this.versionProperties.getGitLatestCommit();
        return Map.of("appName", appname,
                "version", this.versionProperties.getAppVersion(),
                "buildNumber", this.versionProperties.getBuildNumber(),
                "buildDate", this.versionProperties.getBuildDate(),
                "git_latest_commit", gitLatestCommit.substring(0, Integer.min(6, gitLatestCommit.length())),
                "git_branch", this.versionProperties.getGitBranch(),
                "buildMachine", this.versionProperties.getBuildMachine()
        );
    }

    @GetMapping("/teirepos")
    public List<TeiRepoDto> listTeiRepos() {
        // Tests and single-repository deployments may inject a plain TeiRepo;
        // the admin endpoint should report that repository too instead of
        // assuming the production CombinedTeiRepo wiring.
        final List<TeiRepo> repos = this.teiRepo instanceof CombinedTeiRepo combined
                ? combined.getRepos()
                : List.of(this.teiRepo);
        return repos.stream()
                .map( it -> {
                    return TeiRepoDto.builder()
                            .name(it.getName())
                            .enabled(it.isEnabled())
                            .files(it.list())
                            .build();
                })
                .collect(Collectors.toList());
    }


    @PostMapping("/teirepos/reimportFresher")
    public void teiReposReimportFresher() {
        this.adminService.reimportFresherTeis(new NoWriter());
    }


    @PostMapping("/teirepos/reimportAll")
    public void teiReposReimportAll() {
        this.adminService.reimportAllTeis(new NoWriter());
    }

    @PostMapping("/teirepos/reimport")
    public void postTeireposReimport(@RequestParam String file) {
            this.adminService.reimportFile(file, new NoWriter());
    }


    @GetMapping("/teirepos/reimport")
    @ResponseBody
    public String getTeiReposReimport(@RequestParam String file) {
        return "works " + file;
    }


    @PostMapping("/teirepos/forceReimportAll")
    public void teiReposForcefullyReimportAll() {
        this.adminService.destroyAllExistingAndReimportAllTeis(new NoWriter(), true);
    }

    @PostMapping("/teirepos/pruneRemoved")
    public void teiReposPruneRemoved() {
        this.adminService.pruneRemovedTeis(new NoWriter());
    }

    @PostMapping("/teirepos/pruneOrphanedElems")
    public void teiReposPruneOrphanedElems() {
        this.adminService.pruneOrphanedElems(new NoWriter());
    }

    @PostMapping("/lucene/reindex")
    @ResponseBody
    public Map<String, Integer> reindexLucene() {
        return Map.of("indexed", this.adminService.reindexLucene());
    }

    /**
     * Author rows split by name variants - the same person under two or
     * more TEI-header spellings (see Author.nameIdentityKey). Review
     * these before merging; TeifileParser already reuses variant rows
     * for NEW imports, these endpoints repair the EXISTING splits.
     */
    @GetMapping("/authors/name-variants")
    public List<AuthorMergeService.NameVariantGroup> authorNameVariants() {
        return this.authorMergeService.nameVariants();
    }

    /** Folds one duplicate author row into the canonical one - see AuthorMergeService.merge. */
    @PostMapping("/authors/merge")
    public Map<String, Object> mergeAuthors(@RequestParam String canonical, @RequestParam String duplicate) {
        return this.authorMergeService.merge(canonical, duplicate);
    }

    /**
     * Merges every name-variant group with the deterministic canonical
     * pick (most files, tie-broken by lower id). Safe to re-run: a
     * repaired group no longer shows up in the variant list.
     */
    @PostMapping("/authors/merge-all-variants")
    public List<Map<String, Object>> mergeAllAuthorVariants() {
        return this.authorMergeService.mergeAllNameVariants();
    }

    /**
     * Sweeps author rows left with no attached tei_file - the
     * author-table mirror of /teirepos/pruneOrphanedElems, also run
     * hourly by the autoimport scheduler. See
     * AuthorMergeService.pruneOrphanedAuthors.
     */
    @PostMapping("/authors/pruneOrphaned")
    public Map<String, Integer> pruneOrphanedAuthors() {
        return Map.of("pruned", this.authorMergeService.pruneOrphanedAuthors(new NoWriter()));
    }

}
