package ro.editii.scriptorium.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.HandlerMapping;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.fragment.FragmentResolutionService;
import ro.editii.scriptorium.fragment.FragmentText;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.rest.RestUtil;
import ro.editii.scriptorium.service.DivService;

import java.util.Locale;

/**
 * Renders a Fragment (a quotation - see FragmentResolutionService/DotPath) as
 * a standalone, embeddable "quote card" page: GET /quote/{divPath...}?start=
 * &amp;end=. The div path resolves exactly like DivController's normal
 * /{author}/{opus}/... routes (same DivService.getByPath); "quote" is
 * reserved from DivController's author-catchall regex (see AUTHOR_REGEX)
 * so the two routes can't collide.
 */
@Controller
@Log4j2
@RequiredArgsConstructor
public class FragmentController {

    final DivService divService;
    final FragmentResolutionService fragmentResolutionService;
    final ThymeleafViewResolver viewResolver;

    @GetMapping("/quote/**")
    @Transactional
    public void quote(@RequestParam String start,
                       @RequestParam String end,
                       HttpServletRequest request,
                       HttpServletResponse response,
                       Model model) {
        try {
            final String matchedPath = Util.urlDecode(
                    (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE));
            final String divPath = matchedPath.replaceFirst("^/?quote/", "");

            final TeiElem elem = this.divService.getByPath(divPath);
            if (!(elem instanceof TeiDiv div))
                throw new IllegalArgumentException("a fragment's path must resolve to a div/chapter, not " + divPath);

            final FragmentText fragmentText = this.fragmentResolutionService.resolve(div, start, end);

            model.addAttribute("author", div.getAuthor());
            model.addAttribute("opus", div.getOpus());
            model.addAttribute("div", div);
            model.addAttribute("paragraphs", fragmentText.getParagraphs());
            model.addAttribute("sourceUrl", "/" + div.getCompletePath());

            this.viewResolver
                    .resolveViewName("quote", Locale.getDefault())
                    .render(model.asMap(), request, response);
        } catch (ResourceNotFoundException e) {
            RestUtil.throw404(e.getMessage());
        } catch (IllegalArgumentException e) {
            RestUtil.throw400(e.getMessage());
        } catch (Exception e) {
            log.error("Fragment request failed: {}", RestUtil.summarize(e));
            RestUtil.throw500(e);
        }
    }
}
