package ro.editii.scriptorium.tei;

import lombok.Getter;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.model.Languages;

import java.io.File;
import java.io.InputStream;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

public class CombinedTeiRepo implements TeiRepo, Serializable {

    @Getter
    List<TeiRepo> repos;

    @Getter
    String name;

    @Getter
    Properties properties = new Properties();

    public CombinedTeiRepo(Map<String, String> properties, String... repoDirs) {
        this(Arrays.asList(repoDirs).stream().map(it -> new TeiDirRepoImpl(it, properties)).collect(Collectors.toList()));
        this.properties = Util.asProps(properties);
    }
    public CombinedTeiRepo(String... repoDirs) {
        this(Map.of(), repoDirs);
    }

    public CombinedTeiRepo(List<TeiRepo> repos) {
        this.repos = repos;
        this.name = "combined: " + repos.stream().map(TeiRepo::getName).collect(Collectors.joining(","));
    }

    @Override
    public boolean has(String resName) {
        for (TeiRepo r : this.repos) {
            if (r.isEnabled() && r.has(resName))
                return true;
        }
        return false;
    }

    @Override
    public InputStream getStreamForName(String resName) {
        for (TeiRepo r : this.repos) {
            if (r.isEnabled() && r.has(resName))
                return r.getStreamForName(resName);
        }
        throw new IllegalArgumentException("no res named " + resName);
    }

    @Override
    public List<String> list() {
        List<String> res = new ArrayList<>();
        for (TeiRepo r : this.repos)
            if (r.isEnabled()) res.addAll(r.list());
        return res;
    }

    @Override
    public File getFile(String resName) {
        for (TeiRepo r : this.repos) {
            if (r.isEnabled() && r.has(resName))
                return r.getFile(resName);
        }
        throw new IllegalArgumentException("no res named " + resName);
    }

    @Override
    public Languages getLanguageHint(String resName) {
        for (TeiRepo r : this.repos) {
            if (r.isEnabled() && r.has(resName))
                return r.getLanguageHint(resName);
        }
        throw new IllegalArgumentException("no res named " + resName);
    }

    @Override
    public String getRepoNameForFile(String resName) {
        for (TeiRepo r : this.repos) {
            if (r.isEnabled() && r.has(resName))
                return r.getName();
        }
        throw new IllegalArgumentException("no res named " + resName);
    }

    public static Builder builder() { return new Builder() ; }

    public static class Builder {
        String[] dirs;
        Map<String, String> properties  = new HashMap<>();

        public Builder withDirs(String... dirs) {
            this.dirs  = dirs;
            return this;
        }
        public Builder withFilter(String filter) {
            this.properties.put(TeiRepo.PROP_KEY_FILTER, filter);
            return this;
        }

        public CombinedTeiRepo build() {
            return new CombinedTeiRepo(this.properties, this.dirs);
        }
    }

    @Override
    public String toString() {
        return this.getName();
    }
}
