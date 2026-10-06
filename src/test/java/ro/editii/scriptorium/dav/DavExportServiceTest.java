package ro.editii.scriptorium.dav;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.Languages;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiFile;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DavExportServiceTest {
    private final TeiDivRepository repository = mock(TeiDivRepository.class);
    private final DavExportService service = new DavExportService(repository);

    private TeiDiv work;
    private TeiDiv chapter;
    private TeiDiv section;
    private TeiDiv shortWork;

    @BeforeEach
    void setUp() {
        work = div("work", "The Work", null);
        chapter = div("chapter", "Chapter", work);
        section = div("section", "Section", chapter);
        work.setDbChildren(new ArrayList<>(List.of(chapter)));
        chapter.setDbChildren(new ArrayList<>(List.of(section)));
        section.setDbChildren(new ArrayList<>());

        shortWork = div("short_work", "Short Work", null);
        shortWork.setDbChildren(new ArrayList<>());
        // The export uses the lightweight routing projection; keeping the
        // fixture on that API also prevents the test from accidentally
        // relying on full entities and their LOB fields.
        when(repository.findAllOperaRowsForDav()).thenReturn(List.of(
                row(1L, work, 1),
                row(2L, shortWork, 0)));
        when(repository.findOperaByStablePath("writer", work.getUrlFragment()))
                .thenReturn(java.util.Optional.of(work));
        when(repository.findOperaByStablePath("writer", shortWork.getUrlFragment()))
                .thenReturn(java.util.Optional.of(shortWork));
    }

    @Test
    void exposesLanguageAuthorAndSelectedFragmentationFrontier() {
        final DavExportOptions options = DavExportOptions.from("xml", "1.1", null, null);

        assertThat(service.children(resolve(List.of(), options), options))
                .extracting(it -> it.path().getLast())
                .containsExactly("ro");
        assertThat(service.children(resolve(List.of("ro"), options), options))
                .extracting(it -> it.path().getLast())
                .containsExactly("writer");
        assertThat(service.children(resolve(List.of("ro", "writer"), options), options))
                .extracting(it -> it.path().getLast())
                .containsExactly("short_work.xml", "work");
        assertThat(service.children(resolve(List.of("ro", "writer", "work"), options), options))
                .extracting(it -> it.path().getLast())
                .containsExactly("chapter.xml");
    }

    @Test
    void aBranchEndingBeforeRequestedDepthBecomesAFile() {
        final DavExportOptions options = DavExportOptions.from("txt", "1.1.1", null, null);

        assertThat(resolve(List.of("ro", "writer", "short_work.txt"), options).collection()).isFalse();
        assertThat(resolve(List.of("ro", "writer", "work"), options).collection()).isTrue();
        assertThat(resolve(List.of("ro", "writer", "work", "chapter"), options).collection()).isTrue();
        assertThat(resolve(List.of("ro", "writer", "work", "chapter", "section.txt"), options).collection()).isFalse();
        assertThat(service.resolve(List.of("ro", "writer", "work.txt"), options)).isEmpty();
    }

    @Test
    void languageAndAuthorFiltersApplyToDirectPathsAndListings() {
        assertThat(service.children(resolve(List.of(), DavExportOptions.from("txt", "1", "fr", null)),
                DavExportOptions.from("txt", "1", "fr", null))).isEmpty();
        assertThat(service.resolve(List.of("ro"), DavExportOptions.from("txt", "1", null, "other"))).isEmpty();
    }

    private DavResource resolve(List<String> path, DavExportOptions options) {
        return service.resolve(path, options).orElseThrow();
    }

    private TeiDiv div(String fragment, String head, TeiDiv parent) {
        final Author author = new Author();
        author.setStrId("writer");
        author.setFirstName("Ada");
        author.setLastName("Writer");

        final TeiFile file = parent == null ? new TeiFile() : parent.getTeiFile();
        if (parent == null) {
            file.setFilename(fragment + ".xml");
            file.setLanguage(Languages.RO);
            file.setAuthors(List.of(author));
        }

        final TeiDiv div = new TeiDiv();
        div.setUrlFragment(fragment);
        div.setHead(head);
        div.setXpath("/tei:div");
        div.setTeiFile(file);
        div.setParent(parent);
        return div;
    }

    private DavOperaRow row(long id, TeiDiv div, long childCount) {
        return new DavOperaRow(id, div.getTeiFile().getFilename(), div.getUrlFragment(),
                div.getHead(), Languages.RO, "writer", null, "Ada", "Writer", childCount);
    }
}
