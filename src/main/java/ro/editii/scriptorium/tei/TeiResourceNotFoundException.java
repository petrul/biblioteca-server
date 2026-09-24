package ro.editii.scriptorium.tei;

/**
 * Thrown when a TeiRepo is asked for a source file it no longer contains -
 * typically a TeiFile whose DB rows were imported from a corpus version
 * that has since dropped or renamed that file (a scriptorium-masters
 * rebuild, say). Deliberately its own type, not the old bare
 * IllegalArgumentException: TeiSourceMissingAdvice maps it to a clean
 * 404 with a single log line instead of a 500 with a stack trace, while
 * import-side callers keep treating it as just another failing file
 * (it extends RuntimeException like the exception it replaces).
 */
public class TeiResourceNotFoundException extends RuntimeException {

    public TeiResourceNotFoundException(String resName) {
        super("no res named " + resName);
    }

    public TeiResourceNotFoundException(String resName, Throwable cause) {
        super("no res named " + resName, cause);
    }
}
