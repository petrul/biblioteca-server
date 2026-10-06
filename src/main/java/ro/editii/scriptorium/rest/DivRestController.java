package ro.editii.scriptorium.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.dto.TeiElemDto;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.service.ControllerTool;
import ro.editii.scriptorium.service.DivService;
import ro.editii.scriptorium.toc.Toc;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/divs")
@RequiredArgsConstructor
@Log4j2
public class DivRestController {

    final TeiDivRepository teiDivRepository;
    final DivService divService;
    final ControllerTool controllerTool;
    final EntityManager entityManager;

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleTeiFailure(RuntimeException error) {
        final String summary = RestUtil.summarize(error);
        log.error("TEI API request failed: {}", summary, error);

        // Do not turn an expected HTTP error (most importantly a missing opus)
        // into a 500.  Consumers use 404/400 to discard stale Kafka events;
        // returning 500 here makes them retain and retry those events forever.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResponseStatusException responseStatus) {
                return ResponseEntity.status(responseStatus.getStatusCode()).body(summary);
            }
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(summary);
    }

    /**
     * @return TeiElemDto corresponding to path.
     */
    @Operation(operationId = "getElemByPath",  description = "retrieves TeiElemDto information for a given path, i.e. /alecsandri/versuri")
    @GetMapping("")
    @Transactional
    @ResponseBody TeiElemDto get_by_path(
            @RequestParam(name = "path") String path,
            UriComponentsBuilder uriComponentsBuilder) {

        var elem = this.divService.getByPath(path);
        if (!this.entityManager.contains(elem))
            elem =  this.entityManager.merge(elem); // because it may come from cache

        final var dto = elem.toDto(uriComponentsBuilder);
        return dto;
    }

    /**
     * get TeiDiv by long id
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public @ResponseBody TeiDivDto get_id(
            @PathVariable(name = "id") long id,
            UriComponentsBuilder uriComponentsBuilder,
            HttpServletRequest httpServletRequest
    ) {
        final Optional<TeiDiv> teiDivOpt = this.teiDivRepository.findById(id);
        if (teiDivOpt.isEmpty())
            RestUtil.throw404();
        if (!(teiDivOpt.get() instanceof TeiDiv))
            RestUtil.throw404();
        final TeiDiv teiDiv = (TeiDiv) teiDivOpt.get();
        final String baseUrl = uriComponentsBuilder.path("/").toUriString();
        final TeiDivDto dto = TeiDivDto.fromTeiDiv(teiDiv, baseUrl);
        final TeiDivDto[] childrenDtos = teiDiv.getDbChildrenAsDivs().stream()
                .map(it -> TeiDivDto.fromTeiDiv(it,
                        Util.cloneUriComponentBuilder(uriComponentsBuilder, httpServletRequest)))
                .toArray(TeiDivDto[]::new);
        dto.setChildren(childrenDtos);
        return dto;
    }

    /**
     * @return the {@link Toc} of the {@link TeiDiv} indicated by the id param
     */
    @GetMapping("/{id}/toc")
    @Transactional(readOnly = true)
    public @ResponseBody TeiDivDto[] get_id_toc(
            @PathVariable(name = "id") long id,
            @RequestParam(value = "page", defaultValue = "0") int pageNr,
            @RequestParam(value = "size", defaultValue = "20") int pageSize,
            UriComponentsBuilder uriComponentsBuilder,
            HttpServletRequest httpServletRequest
    ) {
        final Optional<TeiDiv> teiDivOpt = this.teiDivRepository.findById(id);
        if (teiDivOpt.isEmpty())
            RestUtil.throw404();
        final TeiDiv teiDiv = teiDivOpt.get();

        final Toc toc = this.divService.getToc(teiDiv.getId());
        final var arr = new ArrayList<TeiDiv>();
        toc.iterator().forEachRemaining(arr::add);

        final TeiDivDto[] teiDivDtoArr = arr.stream()
                .skip(pageNr * pageSize)
                .limit(pageSize)
                .map(it -> TeiDivDto.fromTeiDiv(it, Util.cloneUriComponentBuilder(uriComponentsBuilder, httpServletRequest)))
                .toArray(TeiDivDto[]::new);

        // no need to return the author over and over again in a toc
        Arrays.stream(teiDivDtoArr).sorted().forEach(it -> it.setAuthor(null));
        return teiDivDtoArr;
    }

    /**
     * get an iterator on paragraphs
     * @return non-div elements of a div (normally an opus)
     * @param withContent if parameter present, each TeiElem will contain actual text content
     */
    @Operation(summary = "Get div paragraphs",
            description = "Returns all child elements of the given div")
    @GetMapping("/{divId}/paras")
    @Transactional
    public @ResponseBody TeiElemDto[] get_id_paras(
            @PathVariable(name = "divId")
            @Parameter(description = "the div id. this id must be the database numeric id of a pre-parsed TeiDiv")
            long divId,
            @RequestParam(value = "page", defaultValue = "0") int pageNr,
            @RequestParam(value = "size", defaultValue = "20") int pageSize,
            @RequestParam(value = "withContent", required = false) String withContent,
            UriComponentsBuilder uriComponentsBuilder,
            HttpServletRequest httpServletRequest
    ) {

        final Optional<TeiDiv> teiDivOpt = this.teiDivRepository.findById(divId);
        if (teiDivOpt.isEmpty())
            RestUtil.throw404();

        final var teiDiv = teiDivOpt.get();
        final long startIdx = (long) pageNr * pageSize;
        final var paras = this.divService.getParagraphs(teiDiv, startIdx, pageSize);

        final var dtos = paras.stream()
            .map(it -> {
                final var ucb = Util.cloneUriComponentBuilder(uriComponentsBuilder, httpServletRequest);
                final var dto = it.toDto(ucb);
                if (withContent != null) {
                    final var elemInfo = it.toElemInfo();
                    final var text = this.controllerTool.teiElemToString(elemInfo);
                    dto.setText(text);
                    dto.setText_sha256(Util.sha256Hex(text));
                }
                return dto;
            }).toArray(TeiElemDto[]::new);

        // unneeded information
        Arrays.stream(dtos).forEach(it -> {
            it.setXpath(null);
            it.setUrlFragment(null);
        });

        return dtos;
    }

    @GetMapping("/")
    @Transactional(readOnly = true)
    public List<TeiDivDto> getAllTeiDivs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            UriComponentsBuilder uriComponentsBuilder
    ) {
        // if no path selector was given, retrieve a paged list of all teidivs
        return getAllDivs(page, size, uriComponentsBuilder);
    }

    private List<TeiDivDto> getAllDivs(int page, int size, UriComponentsBuilder uriComponentsBuilder) {
        final Pageable pageRequest = PageRequest.of(page, size);
        final Page<TeiDiv> teiDivs = this.teiDivRepository.findAll(pageRequest);

        final String baseUrl = uriComponentsBuilder.path("/").toUriString();
        log.debug("baseurl {}", baseUrl);
        final List<TeiDivDto> dtoList = teiDivs.stream()
                .map(it -> TeiDivDto.fromTeiDiv(it, baseUrl))
                .collect(Collectors.toList());

        return dtoList;
    }

}
