package ro.editii.scriptorium.rest;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.time.StopWatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dto.AuthorDto;
import ro.editii.scriptorium.dto.EnvelopeDto;
import ro.editii.scriptorium.dto.HitDto;
import ro.editii.scriptorium.dto.HitsDto;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.search.VectorHit;
import ro.editii.scriptorium.search.content.ContentResolver;
import ro.editii.scriptorium.service.ControllerTool;
import ro.editii.scriptorium.service.DbSearchService;
import ro.editii.scriptorium.service.DivService;
import ro.editii.scriptorium.vector.*;

import java.util.List;


@RestController
@RequestMapping("/api/search")
@CrossOrigin
@Log4j2
public class SearchRestController extends CommonControllerUtil {

    // bounds the embedder fallback call in ann() below - see VectorTextSearchService
    // for the same pattern/reasoning.
    private static final int EMBED_TIMEOUT_SECONDS = 15;

    final DbSearchService dbSearchService;
    final VectorTextSearchService vectorTextSearchService;
    final DivService divService;
    @Autowired ControllerTool controllerTool;
    @Autowired VectorCollection vectorCollection;
    @Autowired Embedder embedder;
    @Autowired ContentResolver contentResolver;
    @Autowired VectorSearchAvailability vectorSearchAvailability;

    public SearchRestController(Environment environment,
                                DbSearchService dbSearchService,
                                VectorTextSearchService vectorTextSearchService,
                                DivService divService) {
        super(environment);
        this.dbSearchService = dbSearchService;
        this.vectorTextSearchService = vectorTextSearchService;
        this.divService = divService;
    }

    @GetMapping("/authors")
    @ResponseBody
    public List<HitDto> searchAuthors(@RequestParam(name = "q") @Size(min = 3) String q,
                                      @RequestParam(name="limit", required = false, defaultValue = "10") int limit,
                                      UriComponentsBuilder uriComponentsBuilder,
                                      HttpServletRequest httpServletRequest) {
        final List<Author> authors = this.dbSearchService.findAuthors(q, limit);
        final List<HitDto> resp = authors.stream().map(it -> HitDto.from(it,
                Util.cloneUriComponentBuilder(uriComponentsBuilder, httpServletRequest))).toList();
        resp.forEach(it -> {
            final AuthorDto auth = (AuthorDto) it.getData();
            auth.setImage_href(
                    this.getAuthorThumb(auth.getStrId(),
                            uriComponentsBuilder, httpServletRequest));
        });
        return resp;
    }

    @GetMapping("/divHeads")
    @ResponseBody
    public List<HitDto> searchDivHeads(@RequestParam(name = "q") @Size(min = 3) String q,
                                      @RequestParam(name = "limit", required = false, defaultValue = "10") int limit,
                                      UriComponentsBuilder uriComponentsBuilder) {
        final StopWatch watch = new StopWatch(); watch.start();

        final List<TeiDiv> divs = this.dbSearchService.findDivHeads(q, limit);
        final var resp = divs.stream().map(it -> HitDto.from(it, uriComponentsBuilder)).toList();

        watch.stop();

        log.info(String.format("searchDivHeads q=[%s](%d). took %s",
                Util.maxNCharsEllipsis(q, 10),
                q.length(),
                watch));

        return resp;
    }

    @GetMapping("/milvus")
    @ResponseBody
    public List<HitDto> searchMilvus(
            @RequestParam(name = "q") @Size(min = 3) String q,
            @RequestParam(name = "limit", required = false, defaultValue = "10") int limit,
            UriComponentsBuilder uriComponentsBuilder) {
        final StopWatch watch = new StopWatch(); watch.start();

        final var divs = this.dbSearchService.searchMilvus(q, limit);

        watch.stop();

        final var resp  = divs.stream().map(it -> HitDto.from(it)).toList();

        log.info(String.format("searchMilvus q=[%s](%d). took %s",
                Util.maxNCharsEllipsis(q, 10),
                q.length(),
                watch));

        return resp;

    }

    @GetMapping("/grep")
    @ResponseBody
    public List<HitDto> searchGrep(
            @RequestParam(name = "q") @Size(min = 3) String q,
            @RequestParam(name = "limit", required = false, defaultValue = "10") int limit) {
        final StopWatch watch = new StopWatch(); watch.start();

        final var hits = this.dbSearchService.searchGrep(q, limit);

        watch.stop();

        final var resp = hits.stream().map(HitDto::from).toList();

        log.info(String.format("searchGrep q=[%s](%d). took %s",
                Util.maxNCharsEllipsis(q, 10),
                q.length(),
                watch));

        return resp;
    }

    @GetMapping("/lucene")
    @ResponseBody
    public List<HitDto> searchLucene(
            @RequestParam(name = "q") @Size(min = 3) String q,
            @RequestParam(name = "limit", required = false, defaultValue = "10") int limit,
            UriComponentsBuilder uriComponentsBuilder) {
        final StopWatch watch = new StopWatch(); watch.start();

        final var hits = this.dbSearchService.searchLucene(q, limit);

        watch.stop();

        final var resp = hits.stream().map(HitDto::from).toList();

        log.info(String.format("searchLucene q=[%s](%d). took %s",
                Util.maxNCharsEllipsis(q, 10),
                q.length(),
                watch));

        return resp;
    }

    /**
     * ANN : approximate nearest neighbour
     * nearest k
     */
    @Operation(description = "gets ")
    @GetMapping("/ann")
    public EnvelopeDto.Hits ann(
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "divid", required = false) Long divid,
            UriComponentsBuilder uriComponentsBuilder, HttpServletRequest request
    ) {
        if (!this.vectorSearchAvailability.isAvailable())
            return EnvelopeDto.Hits.builder().data(new HitsDto(new HitDto[0])).build();

        final TeiElem elem;

        if (path == null && divid == null)
            throw new IllegalArgumentException("either 'path' or 'divid' must be provided");

        if (path != null) {
            elem = this.divService.getByPath(path);
        } else {
            elem = this.divService.getById(divid);
        }

        final var elemInfo = elem.toElemInfo();
        final var text = this.controllerTool.teiElemToString(elemInfo);
        final var sha256 = Util.sha256Hex(text);
        final List<VectorHit> hits;
        try {
            final Content content = this.vectorCollection.findBySha256(sha256);
            final float[] vector;
            if (content != null) {
                // it's already stored in the vector collection
                vector = content.getEmbedding();
            } else {
                // it's not stored, need to compute it - bounded so a
                // slow/GPU-contended Ollama degrades this one request to empty
                // results instead of hanging it.
                try {
                    vector = Util.runWithTimeout(() -> this.embedder.encode(text), EMBED_TIMEOUT_SECONDS);
                } catch (Exception e) {
                    log.warn("Embedder call failed/timed out ({}) - degrading to empty results for this ann() call.", e.getMessage());
                    return EnvelopeDto.Hits.builder().data(new HitsDto(new HitDto[0])).build();
                }
            }
            hits = VectorUtils.searchHitsToHits(
                    this.vectorCollection.searchHits(vector, 20), this.contentResolver);
        } catch (RuntimeException e) {
            // A mid-run vector-store outage/restart: the one-shot startup
            // availability flag stays true through it, so without this the
            // raw store exception (connection refused mid-restart) would
            // surface as a 500 on this endpoint. Same degradation as the
            // embedder timeout above; VectorSearchAvailability's periodic
            // re-check flips the flag within its next minute-long interval,
            // after which the isAvailable() guard at the top handles it.
            log.warn("Vector store failed ({}) - degrading to empty results for this ann() call.", e.getMessage());
            return EnvelopeDto.Hits.builder().data(new HitsDto(new HitDto[0])).build();
        }
        final HitDto[] dtos = hits.stream().map(it -> {
            final var dto = HitDto.from(it);
            return dto;
        }).toArray(HitDto[]::new);

        return EnvelopeDto.Hits.builder()
                .data(new HitsDto(dtos))
                .build();
    }
}
