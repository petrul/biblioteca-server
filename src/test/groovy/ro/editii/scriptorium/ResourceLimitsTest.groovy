package ro.editii.scriptorium

import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.util.UriComponentsBuilder
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.rest.AuthorRestController
import ro.editii.scriptorium.rest.SearchRestController
import ro.editii.scriptorium.rest.WorkCatalogRestController
import ro.editii.scriptorium.service.CoverEnrichmentRequests
import ro.editii.scriptorium.service.DbSearchService

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * What biblioteca-server limits, precisely, and when - the specification
 * for ResourceLimits. Every limit is per request: the server never counts
 * requests over time (no rate limits here). A request asking for more is
 * clamped silently to the most it may have, never refused.
 *
 *   limit                                   applies to                                   value
 *   --------------------------------------  -------------------------------------------  ------
 *   catalog page size                       GET /api/works, /api/authors/page            1..100
 *   search results                          GET /api/search/{authors,divHeads,grep,       1..500
 *                                               lucene,vector}  (param: limit)
 *   table-of-contents / paragraphs page     GET /api/divs/{id}/toc, /api/divs/{id}/paras  1..2000
 *   paragraphs one grep search scans        GET /api/search/grep                          50,000
 *   Spring Data REST page size              GET /api/drest/**                             1000
 *
 * Plain instances with Groovy stubs; no Spring context.
 */
class ResourceLimitsTest {

    @Test
    void theLimitsAreTheseAndNoOthers() {
        assertEquals(100, ResourceLimits.CATALOG_PAGE_SIZE_MAX)
        assertEquals(500, ResourceLimits.SEARCH_RESULTS_MAX)
        assertEquals(2000, ResourceLimits.DIV_PAGE_SIZE_MAX)
        assertEquals(50_000, ResourceLimits.GREP_PARAGRAPHS_SCANNED_MAX)
        assertEquals(1000, ResourceLimits.DREST_PAGE_SIZE_MAX)
    }

    @Test
    void anOversizedOrNonsensicalRequestIsClampedNotRefused() {
        assertEquals(100, ResourceLimits.pageSize(1_000_000, ResourceLimits.CATALOG_PAGE_SIZE_MAX))
        assertEquals(1, ResourceLimits.pageSize(0, ResourceLimits.CATALOG_PAGE_SIZE_MAX))
        assertEquals(1, ResourceLimits.pageSize(-5, ResourceLimits.CATALOG_PAGE_SIZE_MAX))
        assertEquals(37, ResourceLimits.pageSize(37, ResourceLimits.CATALOG_PAGE_SIZE_MAX), 'within the limit: untouched')
        assertEquals(1, ResourceLimits.pageNumber(-3))
        assertEquals(0, ResourceLimits.pageIndex(-3))
        assertEquals(500, ResourceLimits.searchResults(Integer.MAX_VALUE))
    }

    @Test
    void everySearchModeReturnsAtMost500Results() {
        final asked = [:]
        final search = new DbSearchService(null, null, null, null, null) {
            List findAuthors(String q, int limit) { asked.authors = limit; [] }
            List findDivHeads(String q, int limit) { asked.divHeads = limit; [] }
            List searchMilvus(String q, int limit) { asked.vector = limit; [] }
            List searchGrep(String q, int limit) { asked.grep = limit; [] }
            List searchLucene(String q, int limit) { asked.lucene = limit; [] }
        }
        final controller = new SearchRestController(new MockEnvironment(), search, null, null)
        final ucb = UriComponentsBuilder.fromUriString('http://test')

        controller.searchAuthors('hugo', 1_000_000, ucb, new MockHttpServletRequest())
        controller.searchDivHeads('hugo', 1_000_000, ucb)
        controller.searchVector('hugo', 1_000_000, ucb)
        controller.searchGrep('hugo', 1_000_000)
        controller.searchLucene('hugo', 1_000_000, ucb)

        assertEquals([authors: 500, divHeads: 500, vector: 500, grep: 500, lucene: 500], asked)
    }

    @Test
    void theWorksAndAuthorsCatalogsServeAtMost100PerPage() {
        Pageable worksAsked = null
        Pageable authorsAsked = null
        final teiDivs = [findOperaCatalogPage: { q, lang, Pageable p -> worksAsked = p; Page.empty(p) }] as TeiDivRepository
        final authors = [findCatalogPage: { q, Pageable p -> authorsAsked = p; Page.empty(p) }] as AuthorRepository

        // An empty page asks for no covers; the subclass only stands in for the bean.
        final covers = new CoverEnrichmentRequests(null) {
            void requestMissing(Collection dtos) {}
        }
        new WorkCatalogRestController(teiDivs, covers).page(-2, 1_000_000, null, null, UriComponentsBuilder.fromUriString('http://test'))
        new AuthorRestController(new MockEnvironment(), teiDivs, authors, null, null, covers)
                .getAuthorPage(0, 1_000_000, null, UriComponentsBuilder.fromUriString('http://test'), new MockHttpServletRequest())

        assertEquals(100, worksAsked.pageSize)
        assertEquals(0, worksAsked.pageNumber, 'page -2 is page 1')
        assertEquals(100, authorsAsked.pageSize)
    }

    @Test
    void springDataRestPageSizeIsConfiguredToTheSameLimit() {
        final props = new Properties()
        getClass().getResourceAsStream('/application.properties').withCloseable { props.load(it) }
        assertEquals(String.valueOf(ResourceLimits.DREST_PAGE_SIZE_MAX), props.getProperty('spring.data.rest.max-page-size'))
    }
}
