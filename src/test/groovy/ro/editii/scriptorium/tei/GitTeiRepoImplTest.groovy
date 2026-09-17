package ro.editii.scriptorium.tei

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.GTestUtil

class GitTeiRepoImplTest {

    @Test
    void readsFilesFromBasePathUsingGlobSpec() {
        File source = new File(GTestUtil.tmpDir())
        source.deleteOnExit()
        new File(source, "books/deep").mkdirs()
        new File(source, "books/root.md").text = "root"
        new File(source, "books/deep/nested.md").text = "nested"
        new File(source, "books/deep/book.tei.xml").text = "tei"
        new File(source, "books/deep/ignored.txt").text = "ignored"

        run(source, "git", "init")
        run(source, "git", "config", "user.email", "test@example.invalid")
        run(source, "git", "config", "user.name", "Test")
        run(source, "git", "add", ".")
        run(source, "git", "commit", "-m", "initial")

        File cache = new File(GTestUtil.tmpDir())
        cache.deleteOnExit()
        def repo = new GitTeiRepoImpl(source.absolutePath, cache.absolutePath, "books", "**/*.md")

        assert repo.list() == ["deep/nested.md", "root.md"]
        assert repo.has("deep/nested.md")
        assert repo.has("/deep/nested.md")
        assert !repo.has("deep/ignored.txt")
        assert repo.getStreamForName("root.md").text == "root"
    }

    @Test
    void defaultSpecAlsoReadsConventionalXmlNames() {
        File source = new File(GTestUtil.tmpDir())
        source.deleteOnExit()
        new File(source, "ro/author").mkdirs()
        new File(source, "ro/author/book.xml").text = "tei"

        run(source, "git", "init")
        run(source, "git", "config", "user.email", "test@example.invalid")
        run(source, "git", "config", "user.name", "Test")
        run(source, "git", "add", ".")
        run(source, "git", "commit", "-m", "initial")

        File cache = new File(GTestUtil.tmpDir())
        cache.deleteOnExit()
        def repo = new GitTeiRepoImpl(source.absolutePath, cache.absolutePath, "", null)

        assert repo.list() == ["ro/author/book.xml"]
        assert repo.has("/ro/author/book.xml")
    }

    private static void run(File directory, String... command) {
        def process = new ProcessBuilder(command).directory(directory).redirectErrorStream(true).start()
        assert process.waitFor() == 0: process.inputStream.text
    }
}
