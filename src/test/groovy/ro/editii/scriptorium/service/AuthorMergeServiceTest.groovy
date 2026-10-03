package ro.editii.scriptorium.service

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.server.ResponseStatusException
import ro.editii.scriptorium.dao.AuthorMediaAssociationRepository
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.DivMediaAssociationRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.media.AuthorMediaAssociation
import ro.editii.scriptorium.media.DivMediaAssociation
import ro.editii.scriptorium.media.MediaRef
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiFile

import java.util.Optional

/**
 * AuthorMergeService repairs the author rows split by name variants -
 * the Alarcon double-identity bug: "Alarcon,Pedro Antonio de" (ro file)
 * and "Alarcón, Pedro Antonio de" (es/de files) are the same person,
 * but the exact-string identity on originalNameInTeiFile gave each
 * spelling its own row, slicing the person's works between them.
 *
 * Everything is mocked; the assertions cover the four invariants of a
 * merge: files re-attached, colliding urlFragments renumbered (paths
 * stay unambiguous), media associations re-pointed (never duplicated
 * against the (path, media_ref) unique constraint), and the duplicate
 * row deleted only after it is file-less.
 */
class AuthorMergeServiceTest {

    private Author canonical
    private Author duplicate
    private TeiFile dupFile
    private TeiDiv collidingOpus
    private TeiDiv distinctOpus

    private AuthorRepository authorRepository
    private TeiFileRepository teiFileRepository
    private TeiDivRepository teiDivRepository
    private AuthorMediaAssociationRepository authorMedia
    private DivMediaAssociationRepository divMedia
    private AuthorMergeService service

    private void setUp() {
        authorRepository = Mockito.mock(AuthorRepository.class)
        teiFileRepository = Mockito.mock(TeiFileRepository.class)
        teiDivRepository = Mockito.mock(TeiDivRepository.class)
        authorMedia = Mockito.mock(AuthorMediaAssociationRepository.class)
        divMedia = Mockito.mock(DivMediaAssociationRepository.class)
        service = new AuthorMergeService(authorRepository, teiFileRepository, teiDivRepository, authorMedia, divMedia)

        canonical = Author.newFromOriginalNameInTeiFile("Alarcón, Pedro Antonio de")
        canonical.setId(1256)
        canonical.setStrId("alarcon_pedro_antonio_de")
        duplicate = Author.newFromOriginalNameInTeiFile("Alarcon,Pedro Antonio de")
        duplicate.setId(1114)
        duplicate.setStrId("alarcon")
        duplicate.setBio("bio from the variant row")

        Mockito.when(authorRepository.getByStrId("alarcon_pedro_antonio_de")).thenReturn(Optional.of(canonical))
        Mockito.when(authorRepository.getByStrId("alarcon")).thenReturn(Optional.of(duplicate))

        dupFile = new TeiFile()
        dupFile.setFilename("ro/alarcon-tricornul.xml")
        dupFile.setAuthors(new ArrayList<>([duplicate]))
        Mockito.when(authorRepository.getTeiFiles(1114)).thenReturn([dupFile])
        Mockito.when(teiFileRepository.saveAllAndFlush(Mockito.anyCollection())).thenAnswer { inv -> inv.getArgument(0) }

        // canonical-native work already occupies the "el-sombrero-de-tres-picos"
        // fragment; the duplicate's translation of the same work collides
        final TeiDiv nativeOpus = new TeiDiv()
        nativeOpus.setHead("El sombrero de tres picos")
        nativeOpus.setUrlFragment("el-sombrero-de-tres-picos")
        Mockito.when(teiDivRepository.findOperaForAuthorStrId("alarcon_pedro_antonio_de")).thenReturn([nativeOpus])

        collidingOpus = new TeiDiv()
        collidingOpus.setHead("El sombrero de tres picos")
        collidingOpus.setUrlFragment("el-sombrero-de-tres-picos")
        distinctOpus = new TeiDiv()
        distinctOpus.setHead("La Prómina")
        distinctOpus.setUrlFragment("la-promina")
        Mockito.when(teiDivRepository.findOperaForAuthorStrId("alarcon")).thenReturn([collidingOpus, distinctOpus])

        Mockito.when(divMedia.findAllByDivPathStartingWith("alarcon/")).thenReturn([])
        Mockito.when(authorMedia.findAllByAuthorPath("alarcon")).thenReturn([])
        Mockito.when(divMedia.existsByDivPathAndMediaRefUrl(Mockito.anyString(), Mockito.anyString())).thenReturn(false)
        Mockito.when(authorMedia.existsByAuthorPathAndMediaRefUrl(Mockito.anyString(), Mockito.anyString())).thenReturn(false)
    }

    @Test
    void mergesFilesFragmentsMediaAndDeletesTheDuplicate() {
        setUp()

        // div-media association on a renumbered path must follow the RENAMED
        // fragment, not the old one
        final divAssoc = new DivMediaAssociation(null, "alarcon/el-sombrero-de-tres-picos",
                MediaRef.builder().url("https://commons/x.jpg").contentType("image/jpeg").role("enrichment").build())
        Mockito.when(divMedia.findAllByDivPathStartingWith("alarcon/")).thenReturn([divAssoc])
        final authorAssoc = new AuthorMediaAssociation(null, "alarcon",
                MediaRef.builder().url("https://commons/y.jpg").contentType("image/jpeg").role("enrichment").build())
        Mockito.when(authorMedia.findAllByAuthorPath("alarcon")).thenReturn([authorAssoc])

        final summary = service.merge("alarcon_pedro_antonio_de", "alarcon")

        // the file moved to the canonical author
        assert dupFile.authors == [canonical]

        // the colliding fragment was renumbered, the distinct one untouched
        assert collidingOpus.urlFragment != "el-sombrero-de-tres-picos"
        assert distinctOpus.urlFragment == "la-promina"
        Mockito.verify(teiDivRepository).save(collidingOpus)
        Mockito.verify(teiDivRepository, Mockito.never()).save(distinctOpus)

        // media followed the strId and the renamed fragment
        assert divAssoc.divPath == "alarcon_pedro_antonio_de/" + collidingOpus.urlFragment
        assert authorAssoc.authorPath == "alarcon_pedro_antonio_de"

        // enrichment moved fill-only (canonical had no bio)
        assert canonical.bio == "bio from the variant row"

        // the duplicate row is gone
        Mockito.verify(authorRepository).delete(duplicate)

        assert summary.canonical == "alarcon_pedro_antonio_de"
        assert summary.duplicateRemoved == "alarcon"
        assert summary.filesReattached == 1
        assert summary.fragmentsRenumbered == 1
        assert summary.divMediaRemapped == 1
        assert summary.authorMediaRemapped == 1
        assert summary.enrichmentFieldsCopied == 1
    }

    @Test
    void dropsMediaTheCanonicalAlreadyHas() {
        setUp()
        // canonical already holds this url for its own path: the unique
        // (path, media_ref) constraint would reject a second association
        Mockito.when(authorMedia.findAllByAuthorPath("alarcon")).thenReturn([
                new AuthorMediaAssociation(null, "alarcon",
                        MediaRef.builder().url("https://commons/y.jpg").contentType("image/jpeg").role("enrichment").build())
        ])
        Mockito.when(authorMedia.existsByAuthorPathAndMediaRefUrl("alarcon_pedro_antonio_de", "https://commons/y.jpg"))
                .thenReturn(true)

        service.merge("alarcon_pedro_antonio_de", "alarcon")

        Mockito.verify(authorMedia, Mockito.never()).save(Mockito.any())
        Mockito.verify(authorMedia).delete(Mockito.any(AuthorMediaAssociation))
    }

    @Test
    void refusesUnknownAuthorsAndSelfMerge() {
        setUp()

        try {
            service.merge("alarcon", "alarcon")
            assert false : "self merge must be rejected"
        } catch (ResponseStatusException expected) { assert expected.statusCode.value() == 400 }

        try {
            service.merge("nimeni", "alarcon")
            assert false : "unknown canonical must 404"
        } catch (ResponseStatusException expected) { assert expected.statusCode.value() == 404 }
    }

    /**
     * The orphan-author sweep - the cleanup the per-file author deletion
     * provably cannot do: a file-less author row is never visited by
     * TeiFileDbService.deleteTeiFile and would stay forever. Three cases:
     * a true orphan is deleted along with its media associations, an
     * author re-attached to a file BETWEEN the listing and the delete is
     * skipped (two importers share one DB, no shared lock), and one
     * failing row never aborts the rest of the sweep.
     */
    @Test
    void sweepsFileLessAuthorRowsAndTheirMediaButSkipsReattachedOnes() {
        setUp()

        final trueOrphan = Author.newFromOriginalNameInTeiFile("Genuinely Orphaned")
        trueOrphan.setId(300)
        trueOrphan.setStrId("orphaned")
        final reattached = Author.newFromOriginalNameInTeiFile("Came Back")
        reattached.setId(301)
        reattached.setStrId("reattached")
        final failing = Author.newFromOriginalNameInTeiFile("Doomed To Fail")
        failing.setId(302)
        failing.setStrId("doomed")

        Mockito.when(authorRepository.findOrphanedAuthors()).thenReturn([trueOrphan, reattached, failing])
        // the sweep must NOT trust the listing: re-verify orphanhood at
        // deletion time
        Mockito.when(authorRepository.getTeiFiles(300)).thenReturn([])
        Mockito.when(authorRepository.getTeiFiles(301)).thenReturn([dupFile]) // a file re-attached it meanwhile
        Mockito.when(authorRepository.getTeiFiles(302)).thenReturn([])
        Mockito.when(authorMedia.findAllByAuthorPath("orphaned")).thenReturn([
                new AuthorMediaAssociation(null, "orphaned",
                        MediaRef.builder().url("https://commons/o.jpg").contentType("image/jpeg").role("enrichment").build())
        ])
        // the third orphan's media query throws - the sweep must continue
        Mockito.when(authorMedia.findAllByAuthorPath("doomed")).thenThrow(new RuntimeException("db hiccup"))
        Mockito.when(authorMedia.findAllByAuthorPath("reattached")).thenReturn([])

        final pruned = service.pruneOrphanedAuthors(new java.io.StringWriter())

        assert pruned == 1
        Mockito.verify(authorRepository).delete(trueOrphan)
        Mockito.verify(authorMedia).delete(Mockito.any(AuthorMediaAssociation))
        Mockito.verify(authorRepository, Mockito.never()).delete(reattached)
        Mockito.verify(authorRepository, Mockito.never()).delete(failing)
    }
}
