package ro.editii.scriptorium.cache;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.file.Files;

@Log4j2
public class DiskCache {

    final File basedir;

    public DiskCache(File basedir) {
        this.basedir = basedir;
        ensureBasedirExists(basedir);
    }

    /**
     * @param basedir the generic directory cache directory containing potentially several caches
     * @param name this cache's name which will be a subdir in basedir
     */
    public DiskCache(File basedir, String name) {
        this(new File(basedir, name));
    }

    /**
     * Written to a temporary file and moved into place, so a concurrent
     * reader never sees half an entry (getAt used to read it, fail, and
     * delete the entry another request had just written).
     */
    public void putAt(Object key, Object value) {
        ensureBasedirExists(basedir);
        final File file = new File(this.basedir, key.toString());
        File tmp = null;
        try {
            tmp = File.createTempFile(".writing-" + key + "-", ".tmp", this.basedir); // a prefix of at least 3 characters
            try (
                final FileOutputStream  fos = new FileOutputStream(tmp);
                final BufferedOutputStream bos = new BufferedOutputStream(fos);
                final ObjectOutputStream oos = new ObjectOutputStream(bos)
            ) {
                oos.writeObject(value);
            }
            Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException  e) {
            if (tmp != null) tmp.delete();
            throw new RuntimeException(e);
        }
    }

    /** Removes one entry; nothing happens if it is not there. */
    public void remove(Object key) {
        new File(this.basedir, key.toString()).delete();
    }

    public boolean has(Object key) {
        final File file = new File(this.basedir, key.toString());
        return file.exists();
    }

    public Object getAt(Object key) {
        final File file = new File(this.basedir, key.toString());
        if (! file.exists())
            return null;

        try (
                final FileInputStream fis = new FileInputStream(file);
                final BufferedInputStream bos = new BufferedInputStream(fis);
                final ObjectInputStream oos = new ObjectInputStream(bos)
        ) {
            return oos.readObject();
        } catch (Exception e) {
            file.delete();
            return null;
        }
    }

    public void delete() {
        try {
            FileUtils.deleteDirectory(this.basedir);
            log.debug("deleted " + this.basedir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void ensureBasedirExists(File dir) {
        if (!dir.exists()) {
            try {
                Files.createDirectories(dir.toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
