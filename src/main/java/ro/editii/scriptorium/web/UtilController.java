package ro.editii.scriptorium.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.DebugUtil;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.TeiDiv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Log4j2
@Controller
@RequestMapping("/util")
public class UtilController {

    @Autowired
    TeiDivRepository teiDivRepository;

    @GetMapping("/echo")
    @ResponseBody
    public ResponseEntity<String> echo(HttpServletRequest request, UriComponentsBuilder uriComponentsBuilder) {
        final String respText = DebugUtil.logHttpRequestHeaders(request, uriComponentsBuilder);
        return ResponseEntity.ok().body(respText);
    }

    @GetMapping("/random")
    @ResponseBody
    // One read-only transaction for the whole random pick: the div's EAGER
    // opusMetadata and the parent-chain walk issue follow-up selects, and
    // without an open transaction each statement auto-commits - after which
    // Derby invalidates the CLOB locators of the div's @Lob columns
    // (XJ215/XJ217) and getSingleResult() fails with "Unable to access lob
    // stream" for roughly every fourth random pick.
    @Transactional(readOnly = true)
    public ResponseEntity<String> random(HttpServletRequest request, UriComponentsBuilder uriComponentsBuilder) {

        TeiDiv div = this.getAcceptableRandomDiv();
        final Author author = div.getTeiFile().getAuthor();
        final List<String> urlFragments = new ArrayList<>(10);

        while (div != null) {
            urlFragments.add(div.getUrlFragment());
            div = (TeiDiv) div.getParent();
        }

        urlFragments.add(author.getStrId());

        Collections.reverse(urlFragments);
        final String redirect_to = urlFragments.stream().collect(Collectors.joining("/"));

        log.info("will redirect to url [{}]", redirect_to);

        final UriComponentsBuilder ucb = Util.cloneUriComponentBuilder(uriComponentsBuilder, request);
        final String url = ucb
                .path(redirect_to)
                .build()
                .toUriString();
        final ResponseEntity<String> resp = ResponseEntity
                .status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .build();
        return resp;
    }

    private TeiDiv getAcceptableRandomDiv() {
        final List<Long> bottomDivIds = this.bottomDivIds();
        if (bottomDivIds.isEmpty()) {
            throw new IllegalStateException("no bottom div found to redirect to");
        }
        // A license div only wastes this pick - license divs are rare, so a
        // fresh random index is cheaper than any filtered query shape. The
        // bound only exists to guarantee a return; 20 bad picks in a row
        // means the corpus is all but entirely license divs.
        final Random rnd = new Random();
        for (int attempt = 0; attempt < 20; attempt++) {
            final TeiDiv div = this.teiDivRepository.findById(bottomDivIds.get(rnd.nextInt(bottomDivIds.size()))).orElse(null);
            if (div != null && !div.isLicense()) {
                return div;
            }
        }
        throw new IllegalStateException("no non-license bottom div found to redirect to");
    }

    // Bottom-div ids for the random pick, cached: the anti-join query
    // costs ~0.5-1s at this table size (no index on the child side), far
    // too much to pay per click. The corpus changes only on import, so a
    // TTL-cached in-memory list turns every pick after the first one
    // into a map lookup plus a single findById.
    private volatile List<Long> bottomDivIdsCache = List.of();
    private volatile long bottomDivIdsCacheAt = 0L;
    private static final long BOTTOM_DIV_IDS_TTL_MS = 30 * 60_000L;

    private List<Long> bottomDivIds() {
        final long now = System.currentTimeMillis();
        if (this.bottomDivIdsCache.isEmpty() || now - this.bottomDivIdsCacheAt > BOTTOM_DIV_IDS_TTL_MS) {
            this.bottomDivIdsCache = this.teiDivRepository.getBottomDivIds();
            this.bottomDivIdsCacheAt = now;
        }
        return this.bottomDivIdsCache;
    }

}
