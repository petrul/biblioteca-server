package ro.editii.scriptorium.service;

import org.junit.jupiter.api.Test;
import ro.editii.scriptorium.dao.AuthorRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiElemRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.dao.TeiOpusRepository;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.model.TeiFile;
import ro.editii.scriptorium.model.Author;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

class TeiFileDbServiceDeletionTest {
    private final TeiFileRepository files = mock(TeiFileRepository.class);
    private final TeiDivRepository divs = mock(TeiDivRepository.class);
    private final TeiOpusRepository opera = mock(TeiOpusRepository.class);
    private final TeiElemRepository elements = mock(TeiElemRepository.class);
    private final AuthorRepository authors = mock(AuthorRepository.class);
    private final TeiFileDbService service = new TeiFileDbService(files, authors,
            divs, opera, null, null, null, null, null, null, null, null, elements);

    private TeiDiv mixedTree() {
        TeiElem note = new TeiElem();
        note.setId(4L);
        // Legacy leaves may have a null rather than initialized children list.
        TeiElem paragraph = new TeiElem();
        paragraph.setId(3L);
        paragraph.setDbChildren(List.of(note));
        TeiDiv chapter = new TeiDiv();
        chapter.setId(2L);
        chapter.setDbChildren(List.of(paragraph));
        TeiDiv opus = new TeiDiv();
        opus.setId(1L);
        opus.setDbChildren(List.of(chapter));
        return opus;
    }

    private void verifyMixedTreeDeleted(TeiDiv root) {
        TeiElem chapter = root.getDbChildren().getFirst();
        TeiElem paragraph = chapter.getDbChildren().getFirst();
        TeiElem note = paragraph.getDbChildren().getFirst();
        var order = inOrder(elements, opera);
        order.verify(elements).delete(note);
        order.verify(elements).delete(paragraph);
        order.verify(opera).deleteByTeiDivId(2L);
        order.verify(elements).delete(chapter);
        order.verify(opera).deleteByTeiDivId(1L);
        order.verify(elements).delete(root);
        verify(opera, never()).deleteByTeiDivId(3L);
        verify(opera, never()).deleteByTeiDivId(4L);
    }

    @Test
    void missingFileCleanupDeletesMixedElementTreeBeforeItsFile() {
        TeiDiv root = mixedTree();
        TeiFile file = new TeiFile();
        file.setId(10L);
        file.setAuthors(List.of());
        when(files.getByFilename("gone.xml")).thenReturn(Optional.of(file));
        when(divs.getOperaForTeiFileId(10L)).thenReturn(List.of(root));

        service.deleteTeiFile("gone.xml");

        verifyMixedTreeDeleted(root);
        var order = inOrder(elements, files);
        order.verify(elements).delete(root);
        order.verify(files).delete(file);
    }

    @Test
    void orphanCleanupAlsoDeletesMixedElementTree() {
        TeiDiv root = mixedTree();

        service.deleteOrphanedElems(root);

        verifyMixedTreeDeleted(root);
        verifyNoInteractions(files);
    }

    @Test
    void locksAuthorsInStableOrderBeforeDeletingAnyElementsOrFiles() {
        TeiDiv root = mixedTree();
        Author first = new Author();
        first.setId(11L);
        Author second = new Author();
        second.setId(22L);
        TeiFile file = new TeiFile();
        file.setId(10L);
        file.setAuthors(List.of(second, first));
        when(files.getByFilename("gone.xml")).thenReturn(Optional.of(file));
        when(divs.getOperaForTeiFileId(10L)).thenReturn(List.of(root));
        when(authors.getTeiFiles(11L)).thenReturn(List.of());
        // An author shared with another source must not be removed.
        when(authors.getTeiFiles(22L)).thenReturn(List.of(new TeiFile()));

        service.deleteTeiFile("gone.xml");

        var order = inOrder(authors, elements, files);
        order.verify(authors).lockForFileDeletion(11L);
        order.verify(authors).lockForFileDeletion(22L);
        order.verify(files).lockForDeletion(10L);
        order.verify(elements).delete(root.getDbChildren().getFirst().getDbChildren().getFirst().getDbChildren().getFirst());
        order.verify(files).delete(file);
        verify(authors).deleteById(11L);
        verify(authors, never()).deleteById(22L);
    }
}
