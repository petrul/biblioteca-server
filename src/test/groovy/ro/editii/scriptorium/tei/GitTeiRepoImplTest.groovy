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

        assert repo.awaitReady(5000)
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

        assert repo.awaitReady(5000)
        assert repo.list() == ["ro/author/book.xml"]
        assert repo.has("/ro/author/book.xml")
    }

    @Test
    void reportsEmptyContentInsteadOfBlockingUntilTheInitialSyncCompletes() {
        // Regression test for the 0.9.3 prod incident: the initial clone/pull
        // used to run inline in the constructor, which blocked this bean's
        // creation, which blocked the whole Spring context (and so Tomcat)
        // from starting - a multi-GB corpus meant the entire app, including
        // unrelated routes like the / -> /app redirect, was unreachable for
        // as long as the clone took. Construction must return immediately
        // and report void content until the background sync finishes.
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
        // autoStart=false: construction does no sync work at all yet, so
        // there's nothing to race - the pre-ready assertions below are
        // deterministic, not "hope the background thread hasn't run yet".
        def repo = new GitTeiRepoImpl(source.absolutePath, cache.absolutePath, "", null, false)

        assert repo.list() == []
        assert !repo.has("ro/author/book.xml")

        repo.startSync()

        assert repo.awaitReady(5000)
        assert repo.list() == ["ro/author/book.xml"]
        assert repo.has("ro/author/book.xml")
    }

    @Test
    void failedSyncDoesNotLeaveABrokenCheckoutBehindForTheNextAttempt() {
        // Regression test for the 2026-09-20 prod incident: a killed/interrupted
        // clone/pull used to leave a half-populated .git in place, so the next
        // attempt saw ".git exists" and tried "pull" against it instead of
        // cloning fresh - never completing, and compounding across restarts
        // until a single repo's checkout dir had grown to 35GB. A failed sync
        // must clean up after itself so the next attempt starts clean.
        File cache = new File(GTestUtil.tmpDir())
        cache.deleteOnExit()
        // A URL that git can resolve as a request but that no repo answers -
        // fails fast without needing network access or a real timeout.
        def repo = new GitTeiRepoImpl("file:///nonexistent/" + UUID.randomUUID(), cache.absolutePath, "", null, false)

        repo.startSync()
        assert !repo.awaitReady(5000)
        assert !repo.list()

        File checkoutDir = new File(cache, "git-repos")
        assert checkoutDir.listFiles() == null || checkoutDir.listFiles().length == 0
    }

    private static void run(File directory, String... command) {
        def process = new ProcessBuilder(command).directory(directory).redirectErrorStream(true).start()
        assert process.waitFor() == 0: process.inputStream.text
    }
}
