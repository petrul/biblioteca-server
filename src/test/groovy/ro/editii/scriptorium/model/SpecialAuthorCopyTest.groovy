package ro.editii.scriptorium.model

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.tei.CandidateStrIdGeneratorForAuthor

/**
 * A special author ("Pline le Jeune", kings and such) is handed out as a copy:
 * the shared instance used to be saved itself, keep the id of a row a
 * rolled-back import never committed, and poison every later import of that
 * author until a restart (docs/caching-plan.md).
 */
class SpecialAuthorCopyTest {

    @Test
    void everyLookupIsAFreshUnsavedCopy() {
        def first = Author.newFromOriginalNameInTeiFile('Pline le Jeune')
        assert first.strId == 'pline_le_jeune' && first.firstName == 'Pline' && first.lastName == 'le Jeune'
        assert first.id == null
        first.id = 993L // what saving it does

        def again = Author.newFromOriginalNameInTeiFile(' Pline le Jeune ')
        assert !again.is(first) && again.id == null
        assert !Author.SPECIAL_AUTHORS['Pline le Jeune'].is(first)
        assert Author.SPECIAL_AUTHORS['Pline le Jeune'].id == null
    }

    @Test
    void aCopyIsStillRecognisedAsSpecial() {
        def copy = Author.newFromOriginalNameInTeiFile('Pline le Jeune')
        assert copy == Author.SPECIAL_AUTHORS['Pline le Jeune'] // equality by names
        assert copy.displayName == 'Pline le Jeune' && copy.originalNameInTeiFile == 'Pline le Jeune'
    }
}
