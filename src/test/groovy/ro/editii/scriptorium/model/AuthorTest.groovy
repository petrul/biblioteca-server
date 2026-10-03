package ro.editii.scriptorium.model

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class AuthorTest {

    // ES-19
    @Test
    public void newFromOriginalNameInTeiFile() {

        final eminescu1 = "Eminescu,Mihai"
        final eminescu2 = "Eminescu, Mihai" // note the space

        final author1 = Author.newFromOriginalNameInTeiFile(eminescu1)
        final author2 = Author.newFromOriginalNameInTeiFile(eminescu2)

        assert author1.lastName == author2.lastName
        assert author1.firstName == author2.firstName
        assert author1.originalNameInTeiFile == author2.originalNameInTeiFile

        final toparceanul = Author.newFromOriginalNameInTeiFile("George Topârceanu")
        assert (toparceanul.firstName == 'George')
        assert (toparceanul.lastName == 'Topârceanu')
    }

    @Test
    public void testStaticPropertiesAreRead() {
        assert Author.FORBIDDEN_AUTHOR_NAMES != null
        assert Author.RECOMMENDED_AUTHOR_MAPPINGS != null

        assert Author.FORBIDDEN_AUTHOR_NAMES.size() > 0
        assert Author.RECOMMENDED_AUTHOR_MAPPINGS.size() > 0

        println Author.FORBIDDEN_AUTHOR_NAMES
        println Author.RECOMMENDED_AUTHOR_MAPPINGS

        assert Author.FORBIDDEN_AUTHOR_NAMES.findAll { it.startsWith("#") }.size() == 0
    }

    @Test
    void testNewFromOriginalNameInTeiFile() {
        final alecsandri = Author.newFromOriginalNameInTeiFile("Vasile Alecsandri")
        assert alecsandri.firstName == 'Vasile'
        assert alecsandri.lastName == 'Alecsandri'

        final reginaMaria = Author.newFromOriginalNameInTeiFile("Regina Maria a României")
        assert reginaMaria.displayName == 'Regina Maria a României'
        assert reginaMaria.firstName == 'Maria'
    }

    // the Alarcon double-identity bug: the corpus spells the same person
    // differently across TEI headers, and the exact-string identity on
    // originalNameInTeiFile split their works into two author rows.
    // nameIdentityKey folds everything that is typography (diacritics,
    // case, punctuation, spacing) or token order - identity stays.
    @Test
    void nameIdentityKeyFoldsTypographyAndTokenOrder() {
        // the actual DB pair: id=1114 [Alarcon,Pedro Antonio de] (ro file,
        // no accent) vs id=1256 [Alarcón,Pedro Antonio de] (es/de files)
        assert Author.nameIdentityKey("Alarcon,Pedro Antonio de")
                == Author.nameIdentityKey("Alarcón, Pedro Antonio de")

        // token order: "Alberdi,Juan Bautista" vs "Bautista Juan,Alberdi"
        assert Author.nameIdentityKey("Alberdi,Juan Bautista")
                == Author.nameIdentityKey("Bautista Juan,Alberdi")

        // punctuation/initials: Chesterton,G. K vs Chesterton,G. K.
        assert Author.nameIdentityKey("Chesterton,G. K")
                == Author.nameIdentityKey("Chesterton,G. K.")

        // hyphens and diacritics: Stevenson-Louis variants, Jókai/Mór
        assert Author.nameIdentityKey("Stevenson,Robert Louis")
                == Author.nameIdentityKey("Stevenson,Robert-Louis")
        assert Author.nameIdentityKey("Jókai,Mór") == Author.nameIdentityKey("Mor,Jokai")
    }

    @Test
    void nameIdentityKeyKeepsDistinctPeopleDistinct() {
        // initials are NOT expanded: F. is not Fenimore
        assert Author.nameIdentityKey("Cooper,James F.") != Author.nameIdentityKey("Cooper,James Fenimore")

        // different given names, same family name
        assert Author.nameIdentityKey("Caragiale,Ion-Luca") != Author.nameIdentityKey("Caragiale,Mateiu")

        // one-named authors
        assert Author.nameIdentityKey("Platón") != Author.nameIdentityKey("Aristóteles")
    }
}
