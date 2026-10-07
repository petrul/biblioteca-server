package ro.editii.scriptorium;

import java.util.concurrent.locks.ReentrantLock;

public class Globals {

    // Reentrant because scheduler/admin entry points call other locked services.
    // Request cleanup must be able to decline this lock without waiting while
    // its open-in-view session still owns a pooled JDBC connection.
    public static final ReentrantLock IMPORT_TEIS_WORKING = new ReentrantLock();

    public static ImportLock lockImports() {
        IMPORT_TEIS_WORKING.lock();
        return new ImportLock();
    }

    /** Null means an import/prune is active; callers must not wait. */
    public static ImportLock tryLockImports() {
        return IMPORT_TEIS_WORKING.tryLock() ? new ImportLock() : null;
    }

    public static final class ImportLock implements AutoCloseable {
        private ImportLock() {}

        @Override
        public void close() {
            IMPORT_TEIS_WORKING.unlock();
        }
    }

}
