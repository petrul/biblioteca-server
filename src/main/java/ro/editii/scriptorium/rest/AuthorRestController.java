package ro.editii.scriptorium.rest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dto.AuthorDto;
import ro.editii.scriptorium.dto.OpusDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.service.DivService;

import java.util.List;
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

    public AuthorRestController(Environment environment, TeiDivRepository teiDivRepository, AuthorRepository authorRepository, DivService divService) {
        super(environment);
        this.teiDivRepository = teiDivRepository;
        this.authorRepository = authorRepository;
        this.divService = divService;
    }

    @GetMapping("/")
    @Transactional(readOnly = true)
    public List<AuthorDto> getAuthors(UriComponentsBuilder uriComponentsBuilder, HttpServletRequest httpServletRequest) {
        log.info("/authors/");
        List<AuthorDto> authors = this.authorRepository.findAll().stream()
                .sorted()
                .map(it -> AuthorDto.from(it))
                .collect(Collectors.toList());

        // add image if existing
        authors.forEach(it -> {
            it.setImage_href(this.getAuthorThumb(it.getStrId(), uriComponentsBuilder, httpServletRequest));
        });
        return authors;
    }


    @GetMapping("/{strId}")
    @Transactional(readOnly = true)
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

        return authorDto;
    }

    @GetMapping("/{strId}/opera")
    @Transactional(readOnly = true)
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
