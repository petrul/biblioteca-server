package ro.editii.scriptorium.tei;

import lombok.extern.log4j.Log4j2;
import ro.editii.scriptorium.model.Languages;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

@Log4j2
public class GitTeiRepoImpl implements TeiRepo {
    private final String url;
    private final Path checkout;
    private final Path root;
    private final String fileSpec;
    private final boolean defaultFileSpec;

    public GitTeiRepoImpl(String url, String workDir, String basePath, String fileSpec) {
        this.url = Objects.requireNonNull(url, "git URL");
        this.defaultFileSpec = fileSpec == null || fileSpec.isBlank();
        this.fileSpec = defaultFileSpec ? "**/*.tei.xml" : fileSpec;
        this.checkout = Path.of(workDir).resolve("git-repos").resolve(hash(url));
        this.root = checkout.resolve(normalizeBasePath(basePath));
        syncCheckout();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Git repo base path is not a directory: " + basePath);
        }
        log.info("Git TEI repo [{}] @ [{}]", url, root);
    }

    @Override
    public String getName() {
        return "git:" + url + (root.equals(checkout) ? "" : "#" + checkout.relativize(root));
    }

    @Override
    public InputStream getStreamForName(String resName) {
        try {
            return Files.newInputStream(resolveResource(resName), StandardOpenOption.READ);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read Git repo resource " + resName, e);
        }
    }

    @Override
    public boolean has(String resName) {
        Path file = resolveResource(resName);
        return Files.isRegularFile(file) && matches(file);
    }

    @Override
    public File getFile(String resName) {
        return resolveResource(resName).toFile();
    }

    @Override
    public List<String> list() {
        try (var paths = Files.walk(root)) {
            List<String> files = paths
                    .filter(Files::isRegularFile)
                    .filter(this::matches)
                    .map(path -> root.relativize(path).toString().replace(File.separatorChar, '/'))
                    .sorted()
                    .collect(Collectors.toList());
            if (files.isEmpty()) {
                log.warn("Git TEI repo {} is empty for file spec {}", getName(), fileSpec);
            }
            return files;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list Git repo " + getName(), e);
        }
    }

    @Override
    public Languages getLanguageHint(String resName) {
        String[] fragments = resName.replace('\\', '/').split("/");
        return Arrays.stream(Languages.values())
                .filter(language -> Arrays.stream(fragments)
                        .anyMatch(fragment -> language.name().equalsIgnoreCase(fragment)))
                .findFirst()
                .orElse(null);
    }

    private boolean matches(Path file) {
        FileSystem fs = file.getFileSystem();
        PathMatcher matcher = fs.getPathMatcher("glob:" + fileSpec);
        Path relative = root.relativize(file);
        if (matcher.matches(relative)) {
            return true;
        }
        if (defaultFileSpec && fs.getPathMatcher("glob:**/*.xml").matches(relative)) {
            return true;
        }
        // Java's glob matcher does not let **/ match a file directly below root.
        return fileSpec.startsWith("**/")
                && fs.getPathMatcher("glob:" + fileSpec.substring(3))
                .matches(relative.getFileName());
    }

    private Path resolveResource(String resName) {
        String relativeName = resName.replaceFirst("^[/\\\\]+", "");
        Path resolved = root.resolve(relativeName).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Resource escapes Git repo base path: " + resName);
        }
        return resolved;
    }

    private void syncCheckout() {
        try {
            Files.createDirectories(checkout.getParent());
            if (Files.isDirectory(checkout.resolve(".git"))) {
                runGit(checkout, "pull", "--ff-only");
            } else {
                runGit(checkout.getParent(), "clone", url, checkout.toString());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare Git repo " + url, e);
        }
    }

    private static void runGit(Path directory, String... args) throws IOException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git command timed out: " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git command", e);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IOException("git command failed (" + process.exitValue() + "): " + output);
        }
    }

    private static String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank() || ".".equals(basePath)) {
            return "";
        }
        Path path = Path.of(basePath).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException("Git repo base path must be relative: " + basePath);
        }
        return path.toString();
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
