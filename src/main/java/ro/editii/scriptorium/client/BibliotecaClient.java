package ro.editii.scriptorium.client;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.dto.EnvelopeDto;
import ro.editii.scriptorium.dto.HitDto;
import ro.editii.scriptorium.dto.TeiDivDto;
import ro.editii.scriptorium.dto.TeiElemDto;

import java.util.List;
import java.util.Map;
import java.util.Optional;


@RequiredArgsConstructor @Log4j2
public class BibliotecaClient {

    public static final String PAGE = "page";
    public static final String SIZE = "size";
    @Getter
    final String baseUrl;
    final RestTemplate restTemplate;

    String url(String path) {
        if (!path.startsWith("/"))
            path = "/" + path;
        final String url = String.format("%s%s", this.baseUrl, path);
        return url;
    }

    public String getTextHtml(String path) {
        final String url = url(path);
        final HttpHeaders headers = acceptHeader(MimeTypeUtils.TEXT_HTML_VALUE);
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final ResponseEntity<String> resp = this.restTemplate.exchange(
                url,
                HttpMethod.GET,
                req, String.class);
        return resp.getBody();
    }


    /**
     * no particular accept HTTP header specified, should return decorated HTML
     */
    public String getDefault(String path) {
        return this.getDefault_ResponseEntity(path).getBody();
    }

    public ResponseEntity<String> getDefault_ResponseEntity(String path) {
        final String url = url(path);
        final HttpHeaders headers = new HttpHeaders() {{
        }};
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final ResponseEntity<String> resp = this.restTemplate.exchange(
                url,
                HttpMethod.GET,
                req, String.class);
        return resp;
    }

    public String getTextXml(String path) {
        final String url = url(path);
        final HttpHeaders headers = acceptHeader(MimeTypeUtils.TEXT_XML_VALUE);
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final ResponseEntity<String> resp = this.restTemplate.exchange(
                url,
                HttpMethod.GET,
                req, String.class);
        return resp.getBody();
    }

    public String getTextPlain(String path) {
        return this.getTextPlain_Response(path).getBody();
    }

    public ResponseEntity<String> getTextPlain_Response(String path) {
        final String url = url(path);
        log.debug("GET " + url);
        final HttpHeaders headers = acceptHeader(MimeTypeUtils.TEXT_PLAIN_VALUE);
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final var uri = UriComponentsBuilder.fromUriString(url).build().toUri();
        final ResponseEntity<String> resp = this.restTemplate.exchange(
                uri,
                HttpMethod.GET,
                req,
                String.class);
        return resp;
    }


    public HitDto[] get_api_search_vector(String query) {
        final var url = url("/api/search/vector") + "?q=" + Util.urlEncode(query);

        final HttpHeaders headers = acceptJsonHeader();
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final ResponseEntity<HitDto[]> resp = this.restTemplate.exchange(
                UriComponentsBuilder.fromUriString(url).build().toUri(),
                HttpMethod.GET,
                req,
                HitDto[].class);
        return resp.getBody();
    }

    public EnvelopeDto.Hits get_api_search_ann(String path) {
        final var url = url("/api/search/ann") + "?path=" + path;

        final var resp = doJsonGet(url, new EnvelopeDto.Hits().getClass());
        return resp;
    }

    public EnvelopeDto.Hits get_api_search_ann(long divid) {
        final var url = url("/api/search/ann") + "?divid=" + divid;

        final var resp = doJsonGet(url, new EnvelopeDto.Hits().getClass());
        return resp;
    }

    /**
     *
     * @param klazz this is needed because I do not know how otherwise to get the runtime class
     *            of the generic type T at runtime.
     */
    private <T> T doJsonGet(String url, Class<T> klazz) {
        final HttpHeaders headers = acceptJsonHeader();
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final ResponseEntity<T> resp = this.restTemplate.exchange(
                UriComponentsBuilder.fromUriString(url).build().toUri(),
                HttpMethod.GET,
                req,
                klazz
                );

        return resp.getBody();
    }


    public HitDto[] get_api_search_authors(String query) {
        final var url = url("/api/search/authors") + "?q=" + Util.urlEncode(query);

        final HttpHeaders headers = acceptJsonHeader();
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final ResponseEntity<HitDto[]> resp = this.restTemplate.exchange(
                UriComponentsBuilder.fromUriString(url).build().toUri(),
                HttpMethod.GET,
                req,
                HitDto[].class);
        return resp.getBody();
    }

    public HitDto[] get_api_search_grep(String query) {
        final var url = url("/api/search/grep") + "?q=" + Util.urlEncode(query);

        final HttpHeaders headers = acceptJsonHeader();
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final ResponseEntity<HitDto[]> resp = this.restTemplate.exchange(
                UriComponentsBuilder.fromUriString(url).build().toUri(),
                HttpMethod.GET,
                req,
                HitDto[].class);
        return resp.getBody();
    }

    public HitDto[] get_api_search_lucene(String query) {
        final var url = url("/api/search/lucene") + "?q=" + Util.urlEncode(query);

        final HttpHeaders headers = acceptJsonHeader();
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final ResponseEntity<HitDto[]> resp = this.restTemplate.exchange(
                UriComponentsBuilder.fromUriString(url).build().toUri(),
                HttpMethod.GET,
                req,
                HitDto[].class);
        return resp.getBody();
    }

    public int post_api_admin_lucene_reindex() {
        final var url = url("/api/admin/lucene/reindex");
        final ResponseEntity<Map> resp = this.restTemplate.postForEntity(url, null, Map.class);
        return ((Number) resp.getBody().get("indexed")).intValue();
    }

    public HitDto[] get_api_search_divHeads(String query) {
        final var url = url("/api/search/divHeads") + "?q=" + Util.urlEncode(query);

        final HttpHeaders headers = acceptJsonHeader();
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final ResponseEntity<HitDto[]> resp = this.restTemplate.exchange(
                UriComponentsBuilder.fromUriString(url).build().toUri(),
                HttpMethod.GET,
                req,
                HitDto[].class);
        return resp.getBody();
    }

    @NotNull
    private static HttpHeaders acceptJsonHeader() {
        return acceptHeader(MimeTypeUtils.APPLICATION_JSON_VALUE);
    }


    @NotNull
    private static HttpHeaders acceptHeader(String acceptValue) {
        return new HttpHeaders() {{ set(ACCEPT, acceptValue); }};
    }

    public TeiElemDto[] get_api_divs_id_paras(long divId) {
        return this.get_api_divs_id_paras(divId, null, null);
    }

    public TeiElemDto[] get_api_divs_id_paras(long divId, Integer page, Integer size) {
        return this.get_api_divs_id_paras(divId, page, size, null);
    }

    public TeiElemDto[] get_api_divs_id_paras(long divId, Integer page, Integer size, Boolean withContent) {
        final var url = url(String.format("/api/divs/%d/paras", divId));
        final UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url)
                .queryParamIfPresent(PAGE, nullableToOptional(page))
                .queryParamIfPresent(SIZE, nullableToOptional(size));

        if (withContent != null && withContent) {
            builder.queryParam("withContent");
        }
        final var urlWithParams = builder.build().toUri();
        final HttpEntity<String> req = new HttpEntity<>(acceptJsonHeader());
        final ResponseEntity<TeiElemDto[]> resp = this.restTemplate.exchange(
                urlWithParams,
                HttpMethod.GET,
                req,
                TeiElemDto[].class);
        return resp.getBody();
    }

    public TeiDivDto[] get_api_divs_id_toc(long divId) {
        return this.get_api_divs_id_toc(divId, 0, 20);
    }

    public TeiDivDto[] get_api_divs_id_toc(long divId, int pageNr, int pageSize) {
        final var url = url(String.format("/api/divs/%d/toc", divId));
        final UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url)
                .queryParamIfPresent(PAGE, nullableToOptional(pageNr))
                .queryParamIfPresent(SIZE, nullableToOptional(pageSize));
        final var urlWithParams = builder.build().toUri();

        final HttpEntity<String> req = new HttpEntity<>(acceptJsonHeader());
        final ResponseEntity<TeiDivDto[]> resp = this.restTemplate.exchange(
                urlWithParams,
                HttpMethod.GET,
                req,
                TeiDivDto[].class);
        return resp.getBody();

    }

    @NotNull
    public static Optional<?> nullableToOptional(Object obj) {
        return obj != null ? Optional.of(obj) : Optional.empty();
    }

    public Object get_api_drest_teiDivs() {
        return this.get_api_drest_teiDivs(0, 20);
    }

    public Object get_api_drest_teiDivs(int page, int size) {
        final var url = url(String.format("/api/drest/teiDivs?page=%d&size=%d", page, size));
        final var headers = acceptJsonHeader();
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final ResponseEntity<Map> resp = this.restTemplate.exchange(
                url,
                HttpMethod.GET,
                req,
                Map.class);

        final var body = resp.getBody();
        final Map embedded = (Map) body.get("_embedded");
        return embedded.get("teiDivs");
    }

    public TeiElemDto get_api_drest_teiDivs_i(Long divId) {
        final String url = url(String.format("/api/drest/teiDivs/%d", divId));
        return this.doJsonGet(url, TeiElemDto.class);
    }

    public TeiElemDto get_api_drest_teiDivs_byPath(String path) {
        final String url = url(String.format("/api/divs?path=%s", path));
        return this.doJsonGet(url, TeiElemDto.class);
    }

    public List<TeiDivDto> get_api_drest_teiDivs_search_findOpera() {
        return this.get_api_drest_teiDivs_search_findOpera(0, 20);
    }

    public List<TeiDivDto> get_api_drest_teiDivs_search_findOpera(int page, int size) {
        final var url = url(String.format("/api/drest/teiDivs/search/findOpera?page=%d&size=%d", page, size));
        final var headers = acceptJsonHeader();
        final HttpEntity<String> req = new HttpEntity<>(headers);
        final ResponseEntity<Map> resp = this.restTemplate.exchange(
                url,
                HttpMethod.GET,
                req,
                Map.class);

        final var body = resp.getBody();
        final Map embedded = (Map) body.get("_embedded");
        return (List<TeiDivDto>) embedded.get("teiDivs");
    }


}
