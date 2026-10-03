package ro.editii.scriptorium.tei

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TextbaseConfig
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiFile

import java.util.Optional

import static ro.editii.scriptorium.GTestUtil.teiOf

import static org.junit.jupiter.api.Assertions.assertThrows


class TeifileParserMockdepsTest {

    /**
     * assert proper calculation of size
     */
    @Test
    void teiDivSize() {

        AuthorRepository authorRepository = Mockito.mock(AuthorRepository.class)
        TeiFileRepository teiFileRepository = Mockito.mock(TeiFileRepository.class)
        TeiDivRepository teiDivRepository = Mockito.mock(TeiDivRepository.class)
        final events = new TestConfig.TestEventPublish()

        final List<TeiDiv> teiDivs = []
        Mockito
            .when(teiDivRepository.saveAll(Mockito.anyCollection()))
            .thenAnswer( inv -> {
                List arg = inv.getArgument(0)
                teiDivs.addAll(arg)
                return arg
            })
        Mockito
            .when(teiDivRepository.saveAllAndFlush(Mockito.anyCollection()))
            .thenAnswer { inv ->
                List arg = inv.getArgument(0)
                teiDivs.addAll(arg)
                return arg
            }

        AuthorStrIdComputer authorStrIdComputer = new AuthorStrIdComputer(authorRepository)
        final cnt = """
            <div>
                <head>head</head>
                <p>pula1</p>
                <p>pula2</p>
            </div>
        """
        final tei = teiOf(cnt)

        final lang = Languages.BG
        final parser = new TeifileParser(teiFileRepository,
                authorRepository,

                teiDivRepository, events,
                new TextbaseConfig(),
                authorStrIdComputer)

        parser.parse(tei, lang)

        assert teiDivs.size() == 1
        final teiDiv = teiDivs.first()
        assert teiDiv.size == "head pula1 pula2".length()
        assert teiDiv.wordSize == 3
        assert teiDiv.lang == lang
    }

    /**
     * The already-imported verdict must precede every save. The race:
     * two importer JVMs share one DB (the in-memory IMPORT_TEIS_WORKING
     * lock is per-JVM); this one loses - its own check still sees the
     * file as imported, but the winner has not committed the author yet,
     * so the author looks new here. TeiFileAlreadyImportedException is
     * checked and default @Transactional does not roll back on checked
     * exceptions, so under the old order the just-saved author committed
     * while the import aborted - an immortal 0-works orphan that
     * deleteTeiFile's author cleanup can never reach (it only inspects
     * the authors of the file it deletes).
     */
    @Test
    void alreadyImportedCheckPrecedesAuthorSave() {

        AuthorRepository authorRepository = Mockito.mock(AuthorRepository.class)
        TeiFileRepository teiFileRepository = Mockito.mock(TeiFileRepository.class)
        TeiDivRepository teiDivRepository = Mockito.mock(TeiDivRepository.class)
        final events = new TestConfig.TestEventPublish()

        // this JVM lost the race: the file is already imported, but the
        // author name is not in the db yet (the winner has not committed)
        Mockito.when(teiFileRepository.getByFilename(Mockito.anyString()))
                .thenReturn(Optional.of(new TeiFile()))
        Mockito.when(authorRepository.getByOriginalNameInTeiFile(Mockito.anyString()))
                .thenReturn(Optional.empty())

        final parser = new TeifileParser(teiFileRepository,
                authorRepository,
                teiDivRepository, events,
                new TextbaseConfig(),
                new AuthorStrIdComputer(authorRepository))

        final tei = teiOf("""
            <div>
                <head>head</head>
                <p>pula1</p>
            </div>
        """)

        assertThrows(TeiFileAlreadyImportedException.class) {
            parser.parse("race-loser.xml", tei, Languages.BG)
        }

        // the whole point: nothing was persisted on the losing pass
        Mockito.verify(authorRepository, Mockito.never()).save(Mockito.any())
        Mockito.verify(teiFileRepository, Mockito.never()).save(Mockito.any())
    }

    /**
     * The Alarcon double-identity bug: the corpus spells one person
     * differently across files. An exact originalNameInTeiFile miss
     * must fall back to the identity-key match and REUSE the existing
     * author row instead of inserting a second one for the same person.
     */
    @Test
    void nameVariantReusesExistingAuthorRow() {

        AuthorRepository authorRepository = Mockito.mock(AuthorRepository.class)
        TeiFileRepository teiFileRepository = Mockito.mock(TeiFileRepository.class)
        TeiDivRepository teiDivRepository = Mockito.mock(TeiDivRepository.class)
        final events = new TestConfig.TestEventPublish()

        // the accented row already imported by the es/de files
        final Author existing = Author.newFromOriginalNameInTeiFile("Alarcón, Pedro Antonio de")
        existing.setStrId("alarcon_pedro_antonio_de")

        // the ro file's spelling: "Alarcon,Pedro Antonio de" - exact
        // lookup misses, identity key hits
        Mockito.when(authorRepository.getByOriginalNameInTeiFile("Alarcon,Pedro Antonio de"))
                .thenReturn(Optional.empty())
        Mockito.when(authorRepository.findAll()).thenReturn([existing])

        final savedFiles = []
        Mockito.when(teiFileRepository.save(Mockito.any(TeiFile))).thenAnswer { inv ->
            final TeiFile f = inv.getArgument(0)
            savedFiles << f
            return f
        }
        Mockito.when(teiDivRepository.saveAllAndFlush(Mockito.anyCollection())).thenAnswer { inv ->
            return inv.getArgument(0)
        }

        final parser = new TeifileParser(teiFileRepository,
                authorRepository,
                teiDivRepository, events,
                new TextbaseConfig(),
                new AuthorStrIdComputer(authorRepository))

        final tei = teiOf("Alarcon,Pedro Antonio de", """
            <div>
                <head>El sombrero de tres picos</head>
                <p>content</p>
            </div>
        """)
        parser.parse("alarcon-ro.xml", tei, Languages.ES)

        // the new file is attached to the EXISTING author row, and no
        // second author row was created for the same person
        assert savedFiles.size() == 1
        assert savedFiles[0].authors == [existing]
        Mockito.verify(authorRepository, Mockito.never()).save(Mockito.any())
    }
}
