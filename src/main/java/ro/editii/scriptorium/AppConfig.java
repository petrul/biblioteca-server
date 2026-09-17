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
        return new RepoSpec(
                parts[0],
                parts.length > 1 ? parts[1] : "",
                parts.length > 2 && !parts[2].isBlank() ? parts[2] : null);
    }

    private record RepoSpec(String url, String basePath, String fileSpec) {}
}
