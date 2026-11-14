package ro.editii.scriptorium.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.editii.scriptorium.service.DivService;

import java.util.List;

/**
 * The caches the nestjs worker checks alongside enrichment (its "cache"
 * module): which works lack a table of contents in the disk cache, and
 * building one. Internal (BIBLIOTECA_INTERNAL_API_TOKEN, SecurityConfig).
 */
@RestController
@RequestMapping("/api/internal/caches")
@RequiredArgsConstructor
@Tag(name = "internal-caches", description = "Cache check for the background worker")
public class CacheRestController {

    final DivService divService;

    public record MissingTocsView(List<Long> missing, int works) {}

    public record TocBuiltView(long opusId, int divisions, long millis) {}

    @GetMapping("/toc")
    @Operation(operationId = "getMissingTocs", summary = "Works whose table of contents is not in the disk cache (at most limit), and how many works there are")
    public MissingTocsView missingTocs(@RequestParam(defaultValue = "100") int limit) {
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit 1..10000");
        final DivService.MissingTocs missing = this.divService.operaMissingToc(limit);
        return new MissingTocsView(missing.missing(), missing.works());
    }

    @PostMapping("/toc/{opusId}")
    @Operation(operationId = "buildToc", summary = "Builds (or reads) a work's table of contents into the cache")
    public TocBuiltView buildToc(@PathVariable long opusId) {
        final long started = System.nanoTime();
        final int divisions = this.divService.getToc(opusId).size();
        return new TocBuiltView(opusId, divisions, (System.nanoTime() - started) / 1_000_000);
    }
}
