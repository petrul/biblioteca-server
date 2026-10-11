package ro.editii.scriptorium.rest

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.dao.AuthorMediaAssociationRepository
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.DivMediaAssociationRepository
import ro.editii.scriptorium.dao.MediaRefRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiOpusRepository
import ro.editii.scriptorium.dto.EnrichmentUpdateDto
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiOpus

/**
 * Covers are fill-only for routine enrichment, but a deliberate re-render
 * (replaceCover: forced runs, the reader's admin "generate cover") must
 * become the work's cover. Groovy map-coerced mocks, no Spring.
 */
class EnrichmentCoverReplaceTest {

    private static List controllerWithCover(String existingCover) {
        def div = new TeiDiv() {
            @Override Long getId() { 42L }
            @Override String getCompletePath() { 'eminescu/poezii' }
        }
        def metadata = new TeiOpus()
        metadata.coverUrl = existingCover
        def divs = [findById: { id -> id == 42L ? Optional.of(div) : Optional.empty() }, save: { it }] as TeiDivRepository
        def opera = [findByTeiDivId: { id -> Optional.of(metadata) }, save: { it }] as TeiOpusRepository
        def controller = new EnrichmentRestController(
                [:] as AuthorRepository, divs, opera, [:] as MediaRefRepository,
                [:] as AuthorMediaAssociationRepository,
                // No image URLs in these updates: the div-image step only checks for existing ones.
                [existsByDivPathAndMediaRefRole: { path, role -> false }] as DivMediaAssociationRepository)
        [controller, metadata]
    }

    private static EnrichmentUpdateDto cover(String url, boolean replace) {
        def update = new EnrichmentUpdateDto()
        update.opusId = 42L
        update.coverUrl = url
        update.replaceCover = replace
        update
    }

    @Test
    void routineEnrichmentOnlyFillsAMissingCover() {
        def (controller, metadata) = controllerWithCover('https://covers/old.png')
        controller.update(cover('https://covers/new.png', false))
        assert metadata.coverUrl == 'https://covers/old.png'

        def (emptyController, emptyMetadata) = controllerWithCover(null)
        emptyController.update(cover('https://covers/new.png', false))
        assert emptyMetadata.coverUrl == 'https://covers/new.png'
    }

    @Test
    void aDeliberateReRenderReplacesTheExistingCover() {
        def (controller, metadata) = controllerWithCover('https://covers/old.png')
        controller.update(cover('https://covers/new.png', true))
        assert metadata.coverUrl == 'https://covers/new.png'
    }
}
