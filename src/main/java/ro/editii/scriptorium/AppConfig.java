package ro.editii.scriptorium;

import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.editii.scriptorium.tei.CombinedTeiRepo;
import ro.editii.scriptorium.tei.GitTeiRepoImpl;
import ro.editii.scriptorium.tei.TeiRepo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.nio.file.Paths;

/**
 * equiv of old application-context.xml but in java
 */
@Configuration @Log
public class AppConfig {

    @Bean
    public TeiRepo teiRepo(@Value("${repo.tei.repos}") String[] repoSpecs,
                           @Value("${work.dir}") String workDir) {
        List<TeiRepo> repos = new ArrayList<>(Arrays.stream(repoSpecs)
                .map(String::trim)
                .filter(dir -> !dir.isBlank())
                .map(AppConfig::parseRepo)
                .map(spec -> createRepo(spec, workDir))
                .toList());
        return new CombinedTeiRepo(repos);
    }

    private static TeiRepo createRepo(RepoSpec spec, String workDir) {
        if (isRemote(spec.url())) {
            return new GitTeiRepoImpl(spec.url(), workDir, spec.basePath(), spec.fileSpec());
        }
        String path = spec.url().startsWith("file:")
                ? Paths.get(URI.create(spec.url())).toString()
                : spec.url();
        path = Paths.get(Util.replaceTilde(path)).resolve(spec.basePath()).normalize().toString();
        return new ro.editii.scriptorium.tei.TeiDirRepoImpl(path,
                Map.of(TeiRepo.PROP_KEY_FILTER,
                        globToRegex(spec.fileSpec() == null ? "**/*.xml" : spec.fileSpec())));
    }

    private static boolean isRemote(String url) {
        return url.startsWith("git@")
                || url.startsWith("ssh://")
                || url.startsWith("git://")
                || url.startsWith("http://")
                || url.startsWith("https://");
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                regex.append('\\');
                regex.append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.toString();
    }

    private static RepoSpec parseRepo(String spec) {
        String[] parts = spec.split("\\|", -1);
        if (parts.length > 3 || parts[0].isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid repo.tei.repos entry; expected url|basepath|filespec: " + spec);
        }
        String url = parts[0];
        String basePath = parts.length > 1 ? parts[1] : "";
        String fileSpec = parts.length > 2 && !parts[2].isBlank() ? parts[2] : null;

        // Accept the intuitive legacy form `/corpus/**/*.xml` as well as
        // the explicit `url|basepath|filespec` form.  Without this split the
        // glob is treated as the repository directory and TeiDirRepoImpl
        // quite correctly rejects it as "not a dir".
        if (fileSpec == null && !isRemote(url)) {
            int wildcard = firstWildcard(url);
            if (wildcard >= 0) {
                int slash = url.lastIndexOf('/', wildcard);
                if (slash > 0) {
                    // Keep the directory as the repository root and retain
                    // the complete relative glob as its filter.
                    fileSpec = url.substring(slash + 1);
                    url = url.substring(0, slash);
                    basePath = "";
                }
            }
        }
        return new RepoSpec(url, basePath, fileSpec);
    }

    private static int firstWildcard(String value) {
        int star = value.indexOf('*');
        int question = value.indexOf('?');
        if (star < 0) return question;
        if (question < 0) return star;
        return Math.min(star, question);
    }

    private record RepoSpec(String url, String basePath, String fileSpec) {}
}
