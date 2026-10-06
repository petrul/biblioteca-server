package ro.editii.scriptorium.web;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
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

    @Autowired
    EntityManager entityManager;

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
        redirect_to.replaceAll("\\/\\/", "\\/");

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
        while (true) {
            final var div = this.getRandomDiv();
            if (!div.isLicense()) {
                return div;
            }
        }
    }

    private TeiDiv getRandomDiv() {
        int nr_divs = this.teiDivRepository.getNrOfBottomDivs();
        int rnd_value = new Random().nextInt(nr_divs);

        final TypedQuery<TeiDiv> query = this.entityManager.createQuery(
                "select parent from TeiDiv parent left outer join parent.dbChildren c where c is null",
                TeiDiv.class);

        query.setFirstResult(rnd_value);
        query.setMaxResults(1);
        final TeiDiv singleResult = query.getSingleResult();
        return singleResult;
    }

}
