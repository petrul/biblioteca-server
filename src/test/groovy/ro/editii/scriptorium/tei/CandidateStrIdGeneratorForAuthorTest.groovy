package ro.editii.scriptorium.tei

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.GTestUtil

import static ro.editii.scriptorium.GTestUtil.*;
import ro.editii.scriptorium.model.Author;

import static org.junit.jupiter.api.Assertions.*;

class CandidateStrIdGeneratorForAuthorTest {


    def p(args) {
        println(args)
    }

    @Test
    void creangaIsRecommendedSoCandidatesMustOnlyContainOneElement() {
        final resourceText = this.class.classLoader.getResourceAsStream("recommended-author-urls.properties").text
//        p resourceText.split("\n").size()
//        p resourceText.split("\n").each  { p it}

        assert resourceText.split("\n").any { it.contains('creanga=')}
        final creanga = Author.newFromOriginalNameInTeiFile('Creangă,Ion')
        final creanga_gen = new CandidateStrIdGeneratorForAuthor(creanga)
        final creanga_candidates = iterable2list(creanga_gen, 4)
//        println creanga_candidates
        assert ["creanga"] == creanga_candidates
    }
    /**
     * this must be a unit test with not reliance on db for author strid generation
     */
    @Test
    void verifyThatRecommendedMappingsAreRespected() {

        def caragiale_mateiu = "Caragiale,Mateiu"
        def caragiale_ion_luca_original = "Caragiale,Ion-Luca"

        def auth_mateiu = Author.newFromOriginalNameInTeiFile(caragiale_mateiu)
        def auth_ion_luca = Author.newFromOriginalNameInTeiFile(caragiale_ion_luca_original)

        def auth_mateiu_gen = new CandidateStrIdGeneratorForAuthor(auth_mateiu)
        def auth_ion_luca_gen = new CandidateStrIdGeneratorForAuthor(auth_ion_luca)

        def candidates_mateiu = iterable2list(auth_mateiu_gen, 3)

        def candidates_ion_luca = iterable2list(auth_ion_luca_gen, 500)

        println candidates_ion_luca
        println candidates_mateiu

        assert ["caragiale"] == candidates_ion_luca
        assert ["caragiale_mateiu", "caragiale_mateiu_2", "caragiale_mateiu_3"] == candidates_mateiu

        def creanga = Author.newFromOriginalNameInTeiFile('Creangă,Ion')
        def creanga_gen = new CandidateStrIdGeneratorForAuthor(creanga)
        def creanga_candidates = iterable2list(creanga_gen, 4)
        println creanga_candidates
        assert ["creanga"] == creanga_candidates

        assert ["dimulescu", "dimulescu_petru", "dimulescu_petru_2"] ==
                iterable2list(new CandidateStrIdGeneratorForAuthor(Author.newFromOriginalNameInTeiFile("Dimulescu,Petru")), 3)

    }

    @Test
    void test_CandidateStrIdGeneratorForAuthor() {

        def author = Author.newFromOriginalNameInTeiFile("Alecsandri, Vasile")
        def gen = new CandidateStrIdGeneratorForAuthor(author)

        def expected_res = ['alecsandri',
                            'alecsandri_vasile',
                            'alecsandri_vasile_2',
                            'alecsandri_vasile_3',
                            'alecsandri_vasile_4']

        def arr = GTestUtil.iterable2list(gen, expected_res.size())

        assert arr == expected_res
    }

    @Test
    void reginaMaria() {
        assert Author.SPECIAL_AUTHORS != null
        assert Author.SPECIAL_AUTHORS.size() > 0

        def regmar = Author.newFromOriginalNameInTeiFile('Regina Maria a României')

        assert regmar.strId == 'maria_de_romania'
        assert regmar.firstName == 'Maria'
        assert regmar.lastName == 'de Hohenzollern-Sigmaringen'
        assert regmar.displayName == 'Regina Maria a României'
        assert regmar.visualName == regmar.displayName

        final gen = new CandidateStrIdGeneratorForAuthor(regmar)
        final arr = GTestUtil.iterable2list(gen, 4)

        p arr

        assert arr.size() == 1
        assert arr[0] == 'maria_de_romania'

    }

    @Test
    void cyrillic() {
        final dostoievskiName = "Достоевский, Федор Михайлович"
        def dosto = Author.newFromOriginalNameInTeiFile(dostoievskiName)
        final gen = new CandidateStrIdGeneratorForAuthor(dosto)
        final candidates =  iterable2list(gen, 5)
        assert candidates.first() == 'dostoyevskii'
    }

    // All anonymous works live under /trad/... - the anonymous author's only
    // possible strId, never trad_2, whatever language spelled it.
    @Test
    void theAnonymousAuthorIsOnlyEverTrad() {
        ['Anon', 'Anónimo', 'Anonyme', 'Anonymous', 'Notar Anonymus'].each { name ->
            final anonymous = Author.newFromOriginalNameInTeiFile(name)
            assert iterable2list(new CandidateStrIdGeneratorForAuthor(anonymous), 5) == ['trad']
        }
    }

    // trad is reserved: a real author of that name cannot take it first.
    @Test
    void noRealAuthorTakesTrad() {
        final named = iterable2list(new CandidateStrIdGeneratorForAuthor(Author.newFromOriginalNameInTeiFile('Trad,Ion')), 3)
        assert !named.contains('trad')
        assert named.first() == 'trad_ion'

        final oneNamed = iterable2list(new CandidateStrIdGeneratorForAuthor(Author.newFromOriginalNameInTeiFile('Trad')), 3)
        assert oneNamed == ['trad_2', 'trad_3', 'trad_4']
    }
}
