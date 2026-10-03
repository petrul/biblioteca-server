package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dto.CatalogPageDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.Languages;

import java.util.List;

/** Root-work catalogue; unlike /api/divs this never returns chapters. */
@RestController
@RequestMapping("/api/works")
@RequiredArgsConstructor
public class WorkCatalogRestController {
    private final TeiDivRepository teiDivRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public CatalogPageDto<TeiDivDto> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String lang,
            UriComponentsBuilder uriComponentsBuilder) {
        final int safePage = Math.max(1, page);
        final int safeSize = Math.min(100, Math.max(1, size));
        final Page<TeiDiv> result = teiDivRepository.findOperaCatalogPage(
                q == null ? null : q.trim(),
                lang == null || lang.isBlank() ? null : Languages.from(lang.trim()),
                PageRequest.of(safePage - 1, safeSize));
        final List<TeiDivDto> items = result.getContent().stream()
                .map(it -> TeiDivDto.fromTeiDiv(it, uriComponentsBuilder))
                .toList();
        return new CatalogPageDto<>(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
    }
}
