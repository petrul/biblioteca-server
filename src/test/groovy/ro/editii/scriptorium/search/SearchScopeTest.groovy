package ro.editii.scriptorium.search

import org.junit.jupiter.api.Test

class SearchScopeTest {

    @Test
    void normalizesAndMatchesCanonicalWorkPrefixes() {
        def scopes = SearchScope.normalize(['/shakespeare/hamlet/', 'tolstoy/war-and-peace', 'shakespeare/hamlet'])

        assert scopes == ['shakespeare/hamlet', 'tolstoy/war-and-peace']
        assert SearchScope.matches('/shakespeare/hamlet/act_1/1', scopes)
        assert SearchScope.matches('tolstoy/war-and-peace', scopes)
        assert !SearchScope.matches('shakespeare/macbeth/act_1/1', scopes)
        assert !SearchScope.matches('shakespeare/hamlet-annotated/1', scopes)
    }

    @Test
    void rejectsMalformedOrOversizedScopes() {
        ['hamlet', '../secret/opus', 'a//b', 'a/b?x', '/a/b/..'].each { value ->
            assert shouldFail(IllegalArgumentException) { SearchScope.normalize([value]) }
        }
        assert shouldFail(IllegalArgumentException) {
            SearchScope.normalize((1..33).collect { "author/opus-${it}".toString() })
        }
    }

    private static Throwable shouldFail(Class<? extends Throwable> type, Closure closure) {
        try {
            closure.call()
        } catch (Throwable error) {
            assert type.isInstance(error)
            return error
        }
        throw new AssertionError("expected ${type.simpleName}")
    }
}
