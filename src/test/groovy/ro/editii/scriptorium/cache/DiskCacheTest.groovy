package ro.editii.scriptorium.cache

import groovy.transform.CompileStatic
import org.apache.commons.io.FileUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class DiskCacheTest {

    File baseDir;

    @BeforeEach
    void beforeEach() {
        final String tmpdir = System.getProperty("java.io.tmpdir")
        this.baseDir = new File(tmpdir, ro.editii.scriptorium.TestUtils.randomString())
        Files.createDirectories(this.baseDir.toPath())
        p "created basedir ${this.baseDir}"
    }

    @AfterEach
    void afterEach() {
        FileUtils.deleteDirectory(this.baseDir)
        p "deleted basedir ${this.baseDir}"
    }

    @Test @CompileStatic
    void putAt() {
        final DiskCache cache = new DiskCache(this.baseDir)
        assert cache['noexist'] == null
        final n = 10
        final data = (1..n).collect {
            final randomString = ro.editii.scriptorium.TestUtils.randomString()
            cache[it] = randomString
            [it, randomString]
        }

        assert this.baseDir.list().length == n
        assert this.baseDir.list().collect { Integer.parseInt(it)}.sort() == (1..n)

        data.each {
            assert cache[it.first()] == it[1]
        }

        assert cache[999332323232] == null

    }

    def p(args) {
        println(args)
    }
}