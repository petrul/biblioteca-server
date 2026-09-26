package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.editii.scriptorium.search.lucene.LuceneIndexService;

/**
 * Operational status, not admin actions - unauthenticated on purpose, same
 * convention as biblioteca-nestjs's own GET /api/status and GET
 * /api/vectorizing (no auth check there either). Progress bars/dashboards
 * reading this shouldn't need an admin session just to show a number.
 */
@RestController
@CrossOrigin
@RequestMapping("/api")
@RequiredArgsConstructor
public class StatusRestController {

    final LuceneIndexService luceneIndexService;

    @GetMapping("/lucene/status")
    public LuceneIndexService.RebuildStatus luceneStatus() {
        return this.luceneIndexService.rebuildStatus();
    }
}
