package ro.editii.scriptorium.tei

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TextbaseConfig
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiFile

/**
 * Anonymous works all live under the one anonymous author (/trad/...), in
 * every language, so their opus url fragment carries the language:
 * /trad/biblia_ortodoxa_ro, /trad/la_bible_..._fr. Named authors' works
 * keep their plain title fragments.
 */
class AnonymousOpusFragmentTest {

    /** A parser whose repository knows only the given works already imported under each author strId. */
    static TeifileParser parserWithExistingOpera(Map<String, List<String>> fragmentsByAuthor) {
        final teiDivRepository = [
                findOperaForAuthorStrId: { String strId ->
                    (fragmentsByAuthor[strId] ?: []).collect { new TeiDiv(urlFragment: it) }
                }
        ] as TeiDivRepository
        new TeifileParser([:] as TeiFileRepository, [:] as AuthorRepository, teiDivRepository,
                new TestConfig.TestEventPublish(), new TextbaseConfig(), null)
    }

    static TeiDiv opus(String head, String authorName, Languages lang) {
        final author = Author.newFromOriginalNameInTeiFile(authorName)
        author.strId = author.anonymous ? Author.TRADITION_STR_ID : 'eminescu'
        new TeiDiv(head: head, lang: lang, teiFile: new TeiFile(authors: [author]))
    }

    @Test
    void anAnonymousWorkFragmentCarriesItsLanguage() {
        final parser = parserWithExistingOpera([:])
        assert parser.compute_unique_head_url_fragment(opus('Biblia Ortodoxa. Sfanta Scriptura', 'Anon', Languages.RO)) == 'biblia_ro'
        assert parser.compute_unique_head_url_fragment(opus('Coranul', 'Anon', Languages.RO)) == 'coranul_ro'
        assert parser.compute_unique_head_url_fragment(opus('Cantar de mio Cid', 'Anónimo', Languages.ES)).endsWith('_es')
    }

    @Test
    void aSecondRomanianBibleGrowsMoreTitleWords() {
        final parser = parserWithExistingOpera([trad: ['biblia_ro']])
        assert parser.compute_unique_head_url_fragment(opus('Biblia Ortodoxa. Sfanta Scriptura', 'Anon', Languages.RO)) == 'biblia_ortodoxa_ro'
    }

    @Test
    void sameTitleInTwoLanguagesStaysApartUnderTrad() {
        final parser = parserWithExistingOpera([trad: ['biblia_ro']])
        assert parser.compute_unique_head_url_fragment(opus('Biblia Ortodoxa. Sfanta Scriptura', 'Anonyme', Languages.FR)) == 'biblia_fr'
    }

    @Test
    void aSameLanguageClashGetsADistinctiveFragment() {
        // two French "La Bible" files (Old and New Testament)
        final parser = parserWithExistingOpera([trad: ['bible_fr']])
        final second = parser.compute_unique_head_url_fragment(opus('La Bible', 'Anon', Languages.FR))
        assert second != 'bible_fr'
        assert second.endsWith('_fr')
    }

    @Test
    void namedAuthorsAndUnknownLanguagesKeepPlainFragments() {
        final parser = parserWithExistingOpera([:])
        assert parser.compute_unique_head_url_fragment(opus('Poezii', 'Eminescu,Mihai', Languages.RO)) == 'poezii'
        assert parser.compute_unique_head_url_fragment(opus('Poezii populare', 'Anon', null)) == 'poezii_populare'
    }
}
