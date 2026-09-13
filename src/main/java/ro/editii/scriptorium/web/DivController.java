package ro.editii.scriptorium.web;

import editii.commons.xml.DomTool;
import editii.commons.xml.XpathTool;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.persistence.EntityManager;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.*;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriComponentsBuilder;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.RelocationRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.model.*;
import ro.editii.scriptorium.rest.RestUtil;
import ro.editii.scriptorium.service.ControllerTool;
import ro.editii.scriptorium.service.DivService;
import ro.editii.scriptorium.service.ElemInfo;
import ro.editii.scriptorium.tei.TeiRepo;
import ro.editii.scriptorium.toc.Toc;
import ro.editii.scriptorium.xslt.XsltTool;

import javax.xml.transform.Transformer;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * "main" controller: serves bits of xml (works, chapters, divs)
 */
@Controller
@RequestMapping("/")
@Log4j2
@RequiredArgsConstructor
public class DivController {

    // author regex : the author must not be a list of words: css, js etc in order to allow service static content (mainly css and js)
    // "quote" reserved for FragmentController's /quote/** route (see there).
    final static String AUTHOR_REGEX = "/{authorId:^(?!(?:css|img|js|api|util|search|p|admin|app|v2|v3|webjars|swagger-ui|swagger-ui.html|quote|dav)$)[a-z0-9\\_]+$}";
    final static String OPUS_REGEX = AUTHOR_REGEX + "/{opusId:^[a-z0-9\\_\\.]+$}";

    final static String DIVPAGE_REGEX = OPUS_REGEX + "/**";

    public static final String _BINARY_MARK = "_binary";

    public static final String BINARY_OBJECT = "binaryObject";
    public static final String XML_ID = "xml:id";

    public static final String EXT_TXT = "txt";
    public static final String EXT_JSON = "json";
    public static final String EXT_XML = "xml";
    public static final String EXT_HTML = "html";

    protected static final String XSLT_TEIDIV_2_HTML_XSL = "xslt/teidiv2html.xsl";
    protected static final String XSLT_XML_TO_JSON_XSL = "xslt/xml-to-json.xsl";

    static ThreadLocal<Transformer> TRANSFORMERS_TEIDIV2HTML = ThreadLocal.withInitial(() -> Util.getTransformer(XSLT_TEIDIV_2_HTML_XSL));

    static ThreadLocal<Transformer> TRANSFORMERS_TEIDIV2JSON = ThreadLocal.withInitial(() -> Util.getTransformer(XSLT_XML_TO_JSON_XSL));

    final AuthorRepository  authorRepository;
    final TeiFileRepository teiFileRepository;
    final TeiDivRepository  teiDivRepository;
    final DivService        divService;
    final RelocationRepository relocationRepository;
    final EntityManager     em;
    final TeiRepo           teiRepo;
    final ThymeleafViewResolver viewResolver;

    @GetMapping("/")
    @Transactional
    public String index(Model model) {
        final List<Author> all = this.authorRepository
                .findAll().stream()
                .sorted()
                .collect(Collectors.toList());
        model.addAttribute("authors", all);
        return "index";
    }

    protected void binaryObject(HttpServletResponse httpServletResponse,
             String authorId, String opusId, String id) {
        try {

            final byte[] binaryObject  = this.divService.getBinaryObject(authorId, opusId, id);

            httpServletResponse.addHeader(HttpHeaders.CONTENT_TYPE, "image/jpeg");
            final ServletOutputStream outputStream = httpServletResponse.getOutputStream();
            outputStream.write(binaryObject);
            outputStream.flush();
            outputStream.close();
        } catch (IOException | IllegalArgumentException e) {
            log.error(e);
            writeErrorToHttpServletResp(httpServletResponse, e, HttpStatus.INTERNAL_SERVER_ERROR.value());
        }
    }

    protected void writeErrorToHttpServletResp(HttpServletResponse httpServletResponse, Exception exception, int code) {
        httpServletResponse.setStatus(code);
        try {
            httpServletResponse.getWriter().println(exception.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping(AUTHOR_REGEX)
    @Transactional
    public ModelAndView get_authorId(
            @PathVariable(name = "authorId") String authorId,
            Model model,
            UriComponentsBuilder uriComponentsBuilder,
            HttpServletRequest httpServletRequest) {

        final Author author = this.retrieveAuthor(authorId);
        model.addAttribute("author", author);

        final List<TeiFile> listTeiFiles = this.teiFileRepository.getTeiFilesForAuthorStrId(authorId);

        model.addAttribute("listTeiFiles", listTeiFiles);

        final List<TeiDiv> rootDivs = this.teiDivRepository
                .findOperaForAuthorStrId(authorId)
                .stream().sorted().toList();

        model.addAttribute("rootDivs", rootDivs);

        final String thisUrl = uriComponentsBuilder
                .path("/{author}")
                .buildAndExpand(author.getStrId())
                .toUriString();

        model.addAttribute("thisUrl", thisUrl);
        model.addAttribute("requestUri", httpServletRequest.getRequestURI());

        final ModelAndView mv = new ModelAndView("author");
        mv.getModel().putAll(model.asMap());
        return mv;
    }

    /**
     * catch-all dispatcher for urls like: /{author}/{opus}/div1/div2/div3.ext
     */
    @GetMapping(value = { OPUS_REGEX, DIVPAGE_REGEX })
    @Transactional
    public void catchAllDivDispatcher(@PathVariable(name = "authorId") String authorId,
                                       @PathVariable(name = "opusId") String opusId,
                                       @RequestParam(name = "depth", required = false)
                                       @Parameter(description = "how many levels of sub-chapters to include below the requested div: " +
                                               "omitted keeps every sub-chapter no matter how deep (default); 0 keeps none, only this " +
                                               "div's own direct content; N keeps sub-chapters up to N levels deep")
                                       Integer depth,
                                       HttpServletRequest request,
                                       HttpServletResponse response,
                                       Model model,
                                       UriComponentsBuilder uriComponentsBuilder) {

        final var path = Util.urlDecode((String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE));
        final var fragms = Util.pathFragments(path);

        assert fragms.length >= 2;
        assert authorId.equals(fragms[0]);
        assert opusId.equals(fragms[1]);

        final var binaryObjId = this.isBinaryObjectReq(fragms);
        if (binaryObjId != null) {
            // request of binaryObject
            this.binaryObject(response, authorId, opusId, binaryObjId);
            return;
        }

        final var lastFragm = fragms[fragms.length - 1];
        final var extension = Util.getExtension(lastFragm);
        final var accept = request.getHeader(HttpHeaders.ACCEPT);

        try {
            // text was specifically requested either by extension or by Accept accept
            if (EXT_TXT.equals(extension) || MimeTypeUtils.TEXT_PLAIN_VALUE.equals(accept)) {
                this.requestForTxt(authorId, request, response,  uriComponentsBuilder, depth);
            } else
            if (EXT_JSON.equals(extension) || MimeTypeUtils.APPLICATION_JSON_VALUE.equals(accept)) {
                this.requestForJson(authorId, request, response, uriComponentsBuilder, depth);
            } else
            if (EXT_XML.equals(extension) || MimeTypeUtils.TEXT_XML_VALUE.equals(accept)) {
                this.requestForXml(authorId, request, response, uriComponentsBuilder, depth);
            } else
            if (EXT_HTML.equals(extension)) {
                // on .html extension, serve undecorated html
                this.requestForUndecoratedHtml(authorId,  request, response, uriComponentsBuilder, depth);
            } else {
                // default to decorated html
                this.requestForDecoratedHtml(authorId, opusId, request, response, model, uriComponentsBuilder, depth);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (ResourceNotFoundException e) {
            RestUtil.throw404(e.getMessage());
        } catch (IllegalArgumentException e) {
            RestUtil.throw400(e.getMessage());
        } catch (Exception e) {
            log.error(e, e);
            RestUtil.throw500(e);
        }
    }

    /**
     * @param fragms
     * @return non-null only if path fragms contain the '_binary' marker,(returns the next fragm(the binobj id)
     */
    private String isBinaryObjectReq(String[] fragms) {

        for (int i = 2; i < fragms.length; i++) {
            // count starts at 2 since first two elements (author and opusid) cannot be '_binary'
            final var crt = fragms[i];
            if (_BINARY_MARK.equals(crt)) {
                if (i + 1 == fragms.length) {
                    throw new IllegalArgumentException("expected binary id immediately after the _binary marker");
                }
                return fragms[i + 1];
            }
        }
        return null;
    }

    private record ElemInfoAndText(ElemInfo elemInfo, String text) {}

    protected ElemInfoAndText _teiDivAsUndecoratedHtml(String authorId,
                                                      HttpServletRequest request,
                                                      UriComponentsBuilder uriComponentsBuilder,
                                                      Integer depth) {
        final ElemInfo elemInfo = this.getElemInfo(authorId, request, uriComponentsBuilder);

        Util.pruneDivsBeyondDepth(elemInfo, depth);

        final Node selectedDiv = elemInfo.getNodeCopy();

        // ../../..
        final var relativeRoot = elemInfo.getTeiElem().getRelativeRoot();

        final var text = XsltTool.apply(getTei2HtmlTransformer(), selectedDiv,
                Map.of(
                        "requestURI", request.getRequestURI(),
                        "author", elemInfo.getAuthor().getVisualName(),
                        "relativeRoot", relativeRoot
                ));

        return new ElemInfoAndText(elemInfo,  text);
    }


    @Transactional
    public void requestForUndecoratedHtml(@PathVariable String authorId,
                                          HttpServletRequest request,
                                          HttpServletResponse response,
                                          UriComponentsBuilder uriComponentsBuilder,
                                          Integer depth) {
        final StopWatch watch = new StopWatch();
        watch.start();
        final var stuff = this._teiDivAsUndecoratedHtml(authorId, request, uriComponentsBuilder, depth);

        response.addHeader(HttpHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_HTML_VALUE + "; charset=utf-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        try {
            IOUtils.write(stuff.text, response.getOutputStream());
            response.getOutputStream().flush();
            response.getOutputStream().close();

            watch.stop();
        } catch (IOException e) {
            log.error(e, e);
            RestUtil.throw500(e);
        }
    }


    @Transactional
    public void requestForDecoratedHtml(@PathVariable String authorId,
                                        @PathVariable String opusId,
                                        HttpServletRequest request,
                                        HttpServletResponse response,
                                        Model model,
                                        UriComponentsBuilder uriComponentsBuilder,
                                        Integer depth) {

        final StopWatch watch = new StopWatch();
        watch.start();

        final var opusIdNoExt = Util.basename(opusId); // eliminate extension if any

        final var elemInfoAndText = this._teiDivAsUndecoratedHtml(authorId, request, uriComponentsBuilder, depth);
        final var ucb = Util.cloneUriComponentBuilder(uriComponentsBuilder, request);

        final var relativeRoot = elemInfoAndText.elemInfo.getTeiElem().getRelativeRoot();

        model.addAttribute("author", elemInfoAndText.elemInfo.getAuthor());
        model.addAttribute("relativeRoot", relativeRoot);
        model.addAttribute("featuredImage", this.getFeaturedImage(
                elemInfoAndText.elemInfo.getNodeCopy(), ucb, authorId, opusIdNoExt));
        model.addAttribute("text", elemInfoAndText.text);
        model.addAttribute("requestUri", request.getRequestURI());

        final var elem = elemInfoAndText.elemInfo.getTeiElem();

        {
            // 3,5 : breadcrumb
            final List<TeiElem> breadcrumb  = elem.getBreadcrumb();
            model.addAttribute("breadcrumb", breadcrumb);
        }

        final TeiDiv op = this.em.merge(elem.getOpus());
        final TeiDiv div = this.em.merge(elem.getDiv());
        op.setTeiRepo(this.teiRepo);
        div.setTeiRepo(this.teiRepo);

        model.addAttribute("op", op);

        {

            // 4. get content of identified div and spit it out
            final List<TeiElem> children = elemInfoAndText.elemInfo.getChildren();
            final Toc toc = this.divService.getToc(op.getId());
            model.addAttribute("elem", elem);
            model.addAttribute("div", div);
            model.addAttribute("prev", toc.prev(div));
            model.addAttribute("next", toc.next(div));
            model.addAttribute("children", children);
         }

        // 5. language, license and sourceDesc
        {
            final String language = div.getTeiLanguage();
            final String license = div.getLicense();

            String sourceDesc = "";
            {
                final Node node_source_desc = div.getSourceDesc();
                if (node_source_desc != null)
                    sourceDesc = XsltTool.apply(getTei2HtmlTransformer(), node_source_desc, Map.of("baseUrl", request.getRequestURI()));
            }

            if (language != null && !language.trim().isEmpty())
                model.addAttribute("language", language);

            if (license != null && !license.trim().isEmpty())
                model.addAttribute("license", license);

            if (sourceDesc != null && !sourceDesc.trim().isEmpty())
                model.addAttribute("source", sourceDesc);
        }

        watch.stop();

        try {
            this.viewResolver
                    .resolveViewName("teidiv", Locale.getDefault())
                    .render(model.asMap(), request, response);
        } catch (Exception e) {
            RestUtil.throw500(e);
        }
    }

    // raw div as tei xml
    @Transactional
    public void requestForXml(String authorId,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              UriComponentsBuilder uriComponentsBuilder,
                              Integer depth) throws IOException {
        final ElemInfo elemInfo = this.getElemInfo(authorId, request, uriComponentsBuilder);

        Util.pruneDivsBeyondDepth(elemInfo, depth);
        final Node selectedDiv = elemInfo.getNodeCopy();

        response.addHeader(HttpHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_XML_VALUE + "; charset=utf-8");

        DomTool.serialize(selectedDiv, response.getOutputStream());
    }


    final ControllerTool controllerTool;

    @Transactional
    public void requestForTxt(String authorId,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              UriComponentsBuilder uriComponentsBuilder,
                              Integer depth) throws IOException {

        final ElemInfo elemInfo = this.getElemInfo(authorId, request, uriComponentsBuilder);

        this.controllerTool.teiElemToText(elemInfo, response, depth);

    }

    @Transactional
    public void requestForJson(@PathVariable String authorId,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               UriComponentsBuilder uriComponentsBuilder,
                               Integer depth) throws IOException {
        final ElemInfo elemInfo = this.getElemInfo(authorId, request, uriComponentsBuilder);

        Util.pruneDivsBeyondDepth(elemInfo, depth);
        final Node selectedDiv = elemInfo.getNodeCopy();

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.addHeader(HttpHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON_VALUE + "; charset=utf-8");

        final var transf  = this.getTei2JsonTransformer();
        XsltTool.apply(transf, selectedDiv,
                response.getOutputStream(),
                Map.of(
                        "use-rabbitfish", "true"
                ));
    }

    /**
     * @return uses the TRANSFORMERS ThreadLocal to return a unique Transformer per thread
     */
    protected Transformer getTei2HtmlTransformer() { return TRANSFORMERS_TEIDIV2HTML.get(); }

    protected Transformer getTei2JsonTransformer() { return TRANSFORMERS_TEIDIV2JSON.get(); }

    private String getFeaturedImage(Node divNode, UriComponentsBuilder ucb, String authorId, String opusId) {
        final XpathTool xt = new XpathTool(divNode);
        String featuredImage = null;
        // a featured image for this div?
        final List<Node> anyImages = new ArrayList<>();
        xt.visit((Node n) -> {
            if (BINARY_OBJECT.equals(n.getNodeName()))
                anyImages.add(n);
            return n;
        });
        if (!anyImages.isEmpty()) {
            final Node imageNode = anyImages.get(0);// take first image
            final Node idAttrNode = imageNode.getAttributes().getNamedItem(XML_ID);
            if (idAttrNode != null) {
                final String imageId = idAttrNode.getNodeValue();
                featuredImage = ucb
                        .path(authorId)
                        .path("/" + opusId)
                        .path("/" + _BINARY_MARK + "/" + imageId)
                        .toUriString();
            }
        }
        if (featuredImage == null) {
            featuredImage = getRandomImageUrl();
        }

        return featuredImage;
    }


    private String getRandomImageUrl() {
        final List<String> urls = imageUrls();
        final int i = new Random().nextInt(urls.size());
        return urls.get(i);
    }


    protected Author retrieveAuthor(String authorStrId) {
        final List<Author> authors = this.authorRepository.findByStrId(authorStrId);
        if (authors != null && authors.size() == 1) {
            return authors.iterator().next();
        } else {
            throw new ResourceNotFoundException(String.format("no author for id [%s]", authorStrId));
        }
    }


    protected ElemInfo getElemInfo(final String authorId,
                                   HttpServletRequest request,
                                   UriComponentsBuilder uriComponentsBuilder) {

        final StopWatch watch = new StopWatch();
        watch.start();

        final ElemInfo.ElemInfoBuilder resp = ElemInfo.builder();
        // 1. get author /author
        resp.author(this.retrieveAuthor(authorId));

        // 2. get root div aka opusId /author/opusId

        final String pathRequestAttribute = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        final String pathNoExt = Util.basename(Util.urlDecode(pathRequestAttribute));

        checkRelocation(pathNoExt, uriComponentsBuilder);

        final TeiElem elem = this.divService.getByPath(pathNoExt);
        resp.teiElem(elem);

        final var children = elem.getDbChildren();
        if (children != null && children.iterator().hasNext())
            children.iterator().next(); // init lazy proxy

        resp.children(children);

        {
            final Node selectedDiv = this.divService.getNode(elem);

            final Node deepCopy = DomTool.deepCopy(selectedDiv);
            resp.nodeCopy(deepCopy);
        }

        watch.stop();
        log.info("{}, took {}", pathNoExt, watch);

        return resp.build();
    }


    protected void checkRelocation(String path, UriComponentsBuilder uriComponentsBuilder) {
        final Optional<Relocation> byId = this.relocationRepository.findById(path);
        if (byId.isPresent()) {
            throw new RelocationException(uriComponentsBuilder, byId.get().getNewPath());
        }
    }


    static List<String> IMAGE_URLS = null;

    synchronized static List<String> imageUrls() {
        if (IMAGE_URLS != null)
            return IMAGE_URLS;

        final var urls = new ArrayList<>(Util.readTextFileWithComments(DivController.class.getClassLoader().getResourceAsStream("beautiful-images.txt")));
        IMAGE_URLS = urls.stream().map(String::trim).toList();
        return IMAGE_URLS;
    }

}


@ResponseStatus(HttpStatus.MOVED_PERMANENTLY)
class RelocationException extends ResponseStatusException {

    private final String path;

    UriComponentsBuilder uriComponentsBuilder;

    public RelocationException(UriComponentsBuilder uriComponentsBuilder, String newPath) {
        super(HttpStatus.MOVED_PERMANENTLY);
        this.uriComponentsBuilder = uriComponentsBuilder;
        this.path = newPath;
    }

    @Override
    public HttpHeaders getHeaders() {
        final HttpHeaders resp = new HttpHeaders();
        resp.addAll(super.getHeaders());
        resp.set(HttpHeaders.LOCATION, uriComponentsBuilder.path(this.path).toUriString());
        return resp;
    }
}
