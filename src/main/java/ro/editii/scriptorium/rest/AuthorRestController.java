package ro.editii.scriptorium.rest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.dao.AuthorMediaAssociationRepository;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dto.AuthorDto;
import ro.editii.scriptorium.dto.AuthorMediaDto;
import ro.editii.scriptorium.dto.CatalogPageDto;
import ro.editii.scriptorium.dto.OpusDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.media.AuthorMediaAssociation;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.service.DivService;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/authors")
@CrossOrigin
@Log4j2
public class AuthorRestController extends CommonControllerUtil {

    final TeiDivRepository teiDivRepository;
    final AuthorRepository authorRepository;
    final DivService divService;
    final AuthorMediaAssociationRepository authorMedia;

    public AuthorRestController(Environment environment, TeiDivRepository teiDivRepository, AuthorRepository authorRepository,
                                DivService divService, AuthorMediaAssociationRepository authorMedia) {
        super(environment);
        this.teiDivRepository = teiDivRepository;
        this.authorRepository = authorRepository;
        this.divService = divService;
        this.authorMedia = authorMedia;
    }

    @GetMapping("/")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public List<AuthorDto> getAuthors(UriComponentsBuilder uriComponentsBuilder, HttpServletRequest httpServletRequest) {
        log.info("/authors/");
        List<AuthorDto> authors = this.authorRepository.findAll().stream()
                .sorted()
                .map(it -> {
                    final AuthorDto dto = AuthorDto.from(it);
                    dto.setWorksCount(this.teiDivRepository.countOperaForAuthorStrId(it.getStrId()));
                    return dto;
                })
                .collect(Collectors.toList());

        // add image if existing
        authors.forEach(it -> {
            it.setImage_href(this.getAuthorThumb(it.getStrId(), uriComponentsBuilder, httpServletRequest));
        });
        return authors;
    }

    /** Online catalogue endpoint: only the requested author page is loaded. */
    @GetMapping("/page")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public CatalogPageDto<AuthorDto> getAuthorPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String q,
            UriComponentsBuilder uriComponentsBuilder,
            HttpServletRequest httpServletRequest) {
        final int safePage = Math.max(1, page);
        final int safeSize = Math.min(100, Math.max(1, size));
        final Page<Author> result = this.authorRepository.findCatalogPage(
                q == null ? null : q.trim(), PageRequest.of(safePage - 1, safeSize));
        final List<AuthorDto> items = result.getContent().stream().map(author -> {
            final AuthorDto dto = AuthorDto.from(author);
            dto.setWorksCount(this.teiDivRepository.countOperaForAuthorStrId(author.getStrId()));
            dto.setImage_href(this.getAuthorThumb(author.getStrId(), uriComponentsBuilder, httpServletRequest));
            return dto;
        }).toList();
        return new CatalogPageDto<>(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
    }


    @GetMapping("/{strId}")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public AuthorDto getAuthor(@PathVariable(name = "strId") String strId, UriComponentsBuilder uriComponentsBuilder, HttpServletRequest httpServletRequest) {
        final Optional<Author> opt = this.authorRepository.getByStrId(strId);
        if (opt.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        final AuthorDto authorDto = AuthorDto.from(opt.get());
        final List<TeiDiv> opera = this.divService.getOpera(authorDto.getStrId());
        final List<TeiDivDto> operadto = opera.stream().map(it -> {
            UriComponentsBuilder ucb = uriComponentsBuilder.cloneBuilder();
            final OpusDto opus = OpusDto.from(it);
            opus.setId(it.getId());
            opus.setUrl(super.devOrProd(it.getCompletePath(), ucb));
            return opus;
        }).collect(Collectors.toList());
        authorDto.setOpera(operadto.toArray(OpusDto[]::new));
        authorDto.setImage_href(this
                .getAuthorImage(authorDto.getStrId(), uriComponentsBuilder, httpServletRequest));
        // Every image associated with the author (enrichment's Wikimedia
        // URLs) - biblioteca-nestjs picks one per cover it orders.
        authorDto.setImageUrls(this.authorMedia.findAllByAuthorPath(authorDto.getStrId()).stream()
                .map(it -> it.getMediaRef().getUrl())
                .distinct()
                .toList());

        return authorDto;
    }

    /**
     * Every author's full set of enrichment-collected media (Wikipedia/
     * Wikimedia image URLs) in one call - the bulk companion to the
     * single-author imageUrls above, for reviewing what art enrichment has
     * gathered across the whole corpus at once. Only authors with at least
     * one image are included; a literal path segment like this one is
     * matched before {strId} regardless of declaration order, so it never
     * collides with GET /api/authors/{strId}.
     */
    @GetMapping("/media")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public List<AuthorMediaDto> getAllAuthorMedia() {
        final Map<String, Author> authorsByStrId = this.authorRepository.findAll().stream()
                .collect(Collectors.toMap(Author::getStrId, it -> it, (a, b) -> a));
        final Map<String, List<String>> urlsByAuthorPath = this.authorMedia.findAll().stream()
                .collect(Collectors.groupingBy(
                        AuthorMediaAssociation::getAuthorPath,
                        Collectors.mapping(it -> it.getMediaRef().getUrl(), Collectors.toList())));
        return urlsByAuthorPath.entrySet().stream()
                .map(entry -> {
                    final Author author = authorsByStrId.get(entry.getKey());
                    return AuthorMediaDto.builder()
                            .strId(entry.getKey())
                            .displayName(author != null ? author.getVisualName() : entry.getKey())
                            .imageUrls(entry.getValue().stream().distinct().toList())
                            .build();
                })
                .sorted(Comparator.comparing(AuthorMediaDto::getStrId))
                .collect(Collectors.toList());
    }

    @GetMapping("/{strId}/opera")
    @Transactional(readOnly = true, isolation = Isolation.READ_UNCOMMITTED)
    public @ResponseBody TeiDivDto[] getOpera(@PathVariable("strId") String strId, UriComponentsBuilder uriComponentsBuilder) {
        final Optional<Author> byStrId = this.authorRepository.getByStrId(strId);
        if (byStrId.isEmpty())
            RestUtil.throw404();

        final Author author = byStrId.get();
        final List<TeiDiv> opera = this.teiDivRepository.findOperaForAuthorStrId(author.getStrId());

        final TeiDivDto[] dtos = opera.stream()
                .map(it ->  TeiDivDto.fromTeiDiv(it, uriComponentsBuilder))
                .toList().toArray(TeiDivDto[]::new);
        return dtos;
    }

}
