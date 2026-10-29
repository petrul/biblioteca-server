package ro.editii.scriptorium.fragment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.service.DivService;

import java.util.*;

@Service
@RequiredArgsConstructor
public class UnifiedFragmentService {
    final DivService divService;

    public record Fragment(String kind, String sourcePath, String selector, String sourceVersion,
                           String canonicalPath, String contextPath, String workId, String workTitle,
                           String chapterId, String chapterTitle, String authorName, String language,
                           String text, String html, int startOffset, int endOffset, List<String> readingPages) {}

    @Transactional(readOnly = true)
    public Fragment resolve(String path, String selector, String revision) {
        if (path == null || path.length() > 2000 || path.contains("?") || path.contains("#") ||
                Arrays.stream(path.split("/")).anyMatch(p -> p.equals(".") || p.equals("..")))
            throw new IllegalArgumentException("Invalid source path");
        if (revision != null && !revision.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid source revision");
        path = path.replaceAll("^/+|/+$", "");
        if (selector == null || selector.isBlank()) {
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (last.startsWith("_") && (last.contains(":") || last.contains("."))) {
                selector = last;
                path = path.substring(0, path.lastIndexOf('/'));
            }
        }
        TeiElem element = divService.getByPath(path);
        if (selector == null || selector.isBlank()) {
            // Persisted divisions keep their page identity, even if anonymously named _N.
            if (element instanceof TeiDiv div) return page(div);
            List<Integer> steps = new ArrayList<>();
            while (!(element instanceof TeiDiv)) {
                steps.addFirst(element.getNth());
                element = element.getParent();
                if (element == null) throw new IllegalArgumentException("Element has no containing source division");
            }
            selector = "_" + String.join(".", steps.stream().map(String::valueOf).toList());
        }
        if (!(element instanceof TeiDiv div)) throw new IllegalArgumentException("Fragment source must be a division");
        FragmentSelector range = FragmentSelector.parse(selector);
        FragmentProjection projection = new FragmentProjection(div.toElemInfo().getNodeCopy());
        // Anonymous genuine divisions remain reading pages, not synthetic quote chapters.
        if (range.end() == null && projection.node(range.start().steps()) instanceof org.w3c.dom.Element node &&
                "div".equals(node.getLocalName() == null ? node.getNodeName() : node.getLocalName())) {
            TeiElem target = div;
            for (int step : range.start().steps()) target = divService.childElem(target, step);
            if (target instanceof TeiDiv targetDiv) return page(targetDiv);
        }
        if (revision != null && !revision.equals(projection.version))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The source revision has changed; this quotation was not relocated");
        int from = projection.boundary(range.start(), false);
        int to = projection.boundary(range.end() == null ? range.start() : range.end(), true);
        if (from >= to || projection.text.substring(from, to).isBlank())
            throw new IllegalArgumentException("Fragment is empty or its endpoints are reversed");
        if (to - from > 30000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Fragment exceeds 30000 characters");
        String source = div.getCompletePath();
        String canonical = "/" + source + "/" + range + "?rev=" + projection.version;
        return new Fragment("fragment", source, range.toString(), projection.version, canonical,
                canonical + "&display=context", div.getOpus().getCompletePath(), div.getOpus().getHead(),
                source, div.getHead(), div.getAuthor().getVisualName(), language(div),
                projection.text.substring(from, to), projection.html(from, to), from, to,
                List.of(pageAt(div, projection, from), pageAt(div, projection, to - 1)));
    }

    private Fragment page(TeiDiv div) {
        String source = div.getCompletePath();
        return new Fragment("page", source, null, null, "/" + source, "/" + source,
                div.getOpus().getCompletePath(), div.getOpus().getHead(), source, div.getHead(),
                div.getAuthor().getVisualName(), language(div), null, null, 0, 0, List.of(source));
    }

    /** Reading divisions at the exact endpoints, including offsets into ancestor divisions. */
    private String pageAt(TeiDiv source, FragmentProjection projection, int position) {
        org.w3c.dom.Node current = projection.root;
        TeiElem element = source;
        TeiDiv page = source;
        while (true) {
            int nth = 0;
            org.w3c.dom.Node found = null;
            for (org.w3c.dom.Node child = current.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof org.w3c.dom.Element)) continue;
                nth++;
                var span = projection.span(child);
                if (span != null && position >= span.start() && position < span.end()) { found = child; break; }
            }
            if (found == null) return page.getCompletePath();
            element = divService.childElem(element, nth);
            if (element instanceof TeiDiv division) page = division;
            current = found;
        }
    }

    @Transactional(readOnly = true)
    public String contextHtml(Fragment fragment) {
        TeiElem source = divService.getByPath(fragment.sourcePath());
        FragmentProjection projection = new FragmentProjection(source.toElemInfo().getNodeCopy());
        if (!projection.version.equals(fragment.sourceVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source changed during fragment resolution");
        return projection.contextHtml(fragment.startOffset(), fragment.endOffset());
    }

    private String language(TeiDiv div) {
        return div.getTeiFile().getLanguage() == null ? "und" : div.getTeiFile().getLanguage().getISO639_1Code();
    }
}
