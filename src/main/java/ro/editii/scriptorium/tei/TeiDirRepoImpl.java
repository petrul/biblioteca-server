package ro.editii.scriptorium.tei;

import lombok.extern.log4j.Log4j2;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.model.Languages;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Log4j2
public class TeiDirRepoImpl implements TeiRepo, Serializable {

    String teiDir;

    Properties properties;


    public String getName() {
        return teiDir;
    }

    public TeiDirRepoImpl(String teiDir, Properties properties) {
        this.teiDir = Util.replaceTilde(teiDir);
        if (properties == null)
            this.properties = new Properties();
        else
            this.properties = properties;

        log.info("TEI Repo @ [" + this.teiDir +"]" );
    }

    public TeiDirRepoImpl(String teiDir, Map<String, String> properties) {
        this(teiDir, Util.asProps(properties));
    }

    public TeiDirRepoImpl(String teiDir, String filter) {
        this(teiDir, Util.asProps(Map.of(PROP_KEY_FILTER, filter)));
    }

    public void setProp(String key, String value) {
        this.properties.setProperty(key, value);
    }

    public String getProp(String key) {
        return this.properties.getProperty(key);
    }

    public String getFilter() {
        return this.getProp(PROP_KEY_FILTER);
    }

    public TeiDirRepoImpl(String teiDir) {
        this(teiDir, (Properties) null);
    }

    public File getFile(String resName) {
        return new File(this.getTeiDir(), resName);
    }

    /**
     * @return if any fragment of the complete path corresponding to resName
     * is one of the codes for languages: 'en', 'fr', 'es' etc.,
     * then report the corresponding language, else null.
     */

    public Languages getLanguageHint(String resName) {
        final List<String> langList = Arrays.stream(Languages.values())
                .map(it -> it.name())
                .map(String::toLowerCase)
                .toList();
        final var completePath = this.getFile(resName);
        final String parent = completePath.getParent();
        final String[] fragments = parent.split("\\/");
        final Optional<String> anyLangHint = Arrays.stream(fragments)
                .filter(fragm ->
                        langList.stream().anyMatch(lang -> lang.equals(fragm)))
                .findFirst();
        if (anyLangHint.isEmpty())
            return null;
        return Languages.from(anyLangHint.get());
    }

    public boolean has(String resName) {
        File file = this.getFile(resName);
        return file.exists();
    }

    public InputStream getStreamForName(String resName) {
        final File file = this.getFile(resName);

        final BufferedInputStream is;
        try {
            is = new BufferedInputStream(new FileInputStream(file));
        } catch (FileNotFoundException e) {
            // has() said yes a moment ago - the file vanished in between.
            // Same missing-source semantics as CombinedTeiRepo's not-found
            // throw: TeiSourceMissingAdvice turns it into a clean 404.
            throw new TeiResourceNotFoundException(resName, e);
        }
        return is;
    }

    public List<String> list() {
        File file = new File(this.getTeiDir());
        if (!file.exists() || !file.isDirectory())
            throw new RuntimeException("file " + this.teiDir + " is not a dir");

        Path teidirPath = Paths.get(this.getTeiDir());

        try {

            final Path finalTeiDirPath;
            if (Files.isSymbolicLink(teidirPath))
                finalTeiDirPath = Paths.get(teidirPath.toRealPath().toString());
            else
                finalTeiDirPath = teidirPath;

            List<String> collect = Files.walk(finalTeiDirPath)
                    .filter(Files::isRegularFile)
                    .map(it -> it.toString())
                    .filter(it -> it.toLowerCase().endsWith(".xml"))
                    .map(it -> it.substring(finalTeiDirPath.toString().length()))
                    .collect(Collectors.toList());

            String filter = this.getFilter();
            if (filter != null && !filter.trim().isEmpty()) {
                    filter = "(?i)" + filter; // make it case insensitive for free

                Pattern ptrn = Pattern.compile(filter);
                collect = collect.stream()
                        .filter(it -> ptrn.matcher(it).find())
                        .collect(Collectors.toList());
            }

            if (collect.size() < 1) {
                log.warn("teirepo " + this.getName() + " is empty");
            }

            return collect;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public String toString() {
        return "dir:" + this.teiDir;
    }

    public String getTeiDir() {
        return Util.replaceTilde(this.teiDir);
    }

}


