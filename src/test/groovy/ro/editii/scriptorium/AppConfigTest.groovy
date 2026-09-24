package ro.editii.scriptorium

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ro.editii.scriptorium.tei.CombinedTeiRepo
import ro.editii.scriptorium.tei.TeiDirRepoImpl
import java.nio.file.Path
import java.nio.file.Files

class AppConfigTest {
    @TempDir Path directory

    @Test
    void initializesMultipleLocalRepositoriesIncludingFileUrlsAndBasePaths() {
        def first = Files.createDirectories(directory.resolve('first/tei'))
        def second = Files.createDirectories(directory.resolve('second'))
        Files.writeString(first.resolve('one.xml'), '<TEI/>')
        Files.writeString(second.resolve('two.xml'), '<TEI/>')
        def specs = [directory.resolve('first').toString() + '|tei|*.xml',
                     second.toUri().toString() + '||*.xml'] as String[]
        def repo = (CombinedTeiRepo) new AppConfig().teiRepo(specs, directory.toString())
        assert repo.repos.size() == 2
        assert repo.repos.every { it instanceof TeiDirRepoImpl }
        assert repo.repos*.name == [first.toString(), second.toString()]
        assert repo.has('one.xml')
        assert repo.has('two.xml')
    }
}
