package ro.editii.scriptorium.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.search.GrepHit;
import ro.editii.scriptorium.search.LuceneHit;
import ro.editii.scriptorium.search.SearchScope;
import ro.editii.scriptorium.search.VectorHit;
import ro.editii.scriptorium.search.grep.GrepSearchService;
import ro.editii.scriptorium.search.lucene.LuceneIndexService;
import ro.editii.scriptorium.vector.VectorTextSearchService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service @RequiredArgsConstructor
public class DbSearchService {

    final AuthorRepository authorRepository;
    final TeiDivRepository teiDivRepository;
    final VectorTextSearchService vectorTextSearchService;
    final LuceneIndexService luceneIndexService;
    final GrepSearchService grepSearchService;

    /**
     * @param limit max length of response
     * @return authors having the search string in their lastname, firstname or strid
     */
    public List<Author> findAuthors(String q, int limit) {
        assert limit > 0;
        final var page = PageRequest.of(0, limit);
        final List<Author> lastnames  = this.authorRepository.findByLastNameContainingIgnoreCase(q, page).stream().toList();
        final List<Author> firstnames  = this.authorRepository.findByFirstNameContainingIgnoreCase(q, page).stream().toList();
        final List<Author> strids = this.authorRepository.findByStrIdContainingIgnoreCase(q, page).stream().toList();

        final Set<Author> all = new HashSet<>(limit);
        all.addAll(lastnames);
        all.addAll(firstnames);
        all.addAll(strids);

        List<Author> resp = new ArrayList<>(limit);
        resp.addAll(all);

        if (resp.size() > limit)
            resp = resp.subList(0, limit);

        return resp;
    }

    public List<TeiDiv> findDivHeads(String q, int limit) {
        assert limit > 0;
        final var page = PageRequest.ofSize(limit);
        final List<TeiDiv> divs = this.teiDivRepository.findByHeadContainingIgnoreCase(q, page).stream().toList();
        return  divs;
    }

    public List<TeiDiv> findDivHeads(String q, int limit, List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) return findDivHeads(q, limit);
        // Complete descendant paths are derived from the parent chain rather
        // than stored as a database column. Apply the scope before limiting so
        // unrelated headings cannot consume the requested result count.
        return this.teiDivRepository.findByHeadContainingIgnoreCase(q).stream()
                .filter(div -> SearchScope.matches(div.getCompletePath(), scopes))
                .limit(limit)
                .toList();
    }

    public List<VectorHit> searchMilvus(String q, int limit) {
        assert limit > 0;
        final var resp = this.vectorTextSearchService.search(q, limit);
        return resp;
    }

    public List<VectorHit> searchMilvus(String q, int limit, List<String> scopes) {
        assert limit > 0;
        if (scopes == null || scopes.isEmpty()) return searchMilvus(q, limit);
        return this.vectorTextSearchService.search(q, limit, scopes);
    }

    public List<LuceneHit> searchLucene(String q, int limit) {
        assert limit > 0;
        return this.luceneIndexService.search(q, limit);
    }

    public List<LuceneHit> searchLucene(String q, int limit, List<String> scopes) {
        assert limit > 0;
        if (scopes == null || scopes.isEmpty()) return searchLucene(q, limit);
        return this.luceneIndexService.search(q, limit, scopes);
    }

    public List<GrepHit> searchGrep(String q, int limit) {
        assert limit > 0;
        return this.grepSearchService.search(q, limit);
    }

    public List<GrepHit> searchGrep(String q, int limit, List<String> scopes) {
        assert limit > 0;
        if (scopes == null || scopes.isEmpty()) return searchGrep(q, limit);
        return this.grepSearchService.search(q, limit, scopes);
    }
}
