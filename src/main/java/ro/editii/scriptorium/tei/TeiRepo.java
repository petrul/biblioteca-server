package ro.editii.scriptorium.tei;

import ro.editii.scriptorium.model.Languages;

import java.io.File;
import java.io.InputStream;
import java.util.List;

public interface TeiRepo {
    String PROP_KEY_FILTER = "filter";

    String getName();
    default boolean isEnabled() { return true; }
    InputStream getStreamForName(String resName);
    boolean has(String resName);
    File getFile(String resName);
    List<String> list();
    Languages getLanguageHint(String resName);

    /**
     * @return the name of the specific repo resName actually lives in - for
     * a plain (non-combined) repo that's just this repo's own name, but
     * CombinedTeiRepo overrides this to identify which of its several
     * sub-repos owns a given file (see TeiFile.repoName, populated at
     * import time in TeiFileDbService).
     */
    default String getRepoNameForFile(String resName) {
        return this.getName();
    }
}
