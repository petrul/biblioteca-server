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
    private final String basePathForErrorMessage;
    // The initial clone (and every subsequent pull - see syncCheckout) can take
    // minutes for a large repo; doing it inline here would block this bean's
    // construction, which blocks the whole application context from
    // finishing startup, which blocks Tomcat from accepting any request at
    // all - including for content this repo has nothing to do with. Runs on
    // its own low-priority daemon thread instead (same reasoning/pattern as
    // LuceneIndexService.autoBuildIndexOnStartup): list()/has() report this
    // repo as empty until the first sync completes, rather than the whole
    // server refusing connections until a multi-GB clone finishes.
    private volatile boolean ready = false;
    private final java.util.concurrent.CountDownLatch initialSyncDone = new java.util.concurrent.CountDownLatch(1);

    public GitTeiRepoImpl(String url, String workDir, String basePath, String fileSpec) {
        this(url, workDir, basePath, fileSpec, true);
    }

    /**
     * @param autoStart false skips starting the background sync thread from
     *                  the constructor - only for tests that need to assert
     *                  the pre-ready (empty/false) state deterministically,
     *                  without racing a real background thread. Call
     *                  {@link #startSync()} when ready to let it run.
     */
    GitTeiRepoImpl(String url, String workDir, String basePath, String fileSpec, boolean autoStart) {
        this.url = Objects.requireNonNull(url, "git URL");
        this.defaultFileSpec = fileSpec == null || fileSpec.isBlank();
        this.fileSpec = defaultFileSpec ? "**/*.tei.xml" : fileSpec;
        this.checkout = Path.of(workDir).resolve("git-repos").resolve(hash(url));
        this.root = checkout.resolve(normalizeBasePath(basePath));
        this.basePathForErrorMessage = basePath;
        if (autoStart) {
            startSync();
        }
    }

    /** Starts the background initial-sync thread. Idempotent-by-convention only
     *  in that production always calls it exactly once (from the constructor);
     *  tests using the autoStart=false constructor call it exactly once too,
     *  whenever they're ready to let the sync proceed. */
    void startSync() {
        final Thread thread = new Thread(() -> {
            try {
                syncCheckout();
                if (!Files.isDirectory(root)) {
                    throw new IllegalArgumentException(
                            "Git repo base path is not a directory: " + basePathForErrorMessage);
                }
                ready = true;
                log.info("Git TEI repo [{}] @ [{}] ready.", url, root);
            } catch (Exception e) {
                log.error("Initial sync of Git TEI repo [{}] failed - it will report as empty "
                        + "until the app is restarted (no automatic retry).", url, e);
            } finally {
                initialSyncDone.countDown();
            }
        }, "git-tei-sync-" + hash(url).substring(0, 8));
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /**
     * Blocks until the initial clone/pull finishes (successfully or not) or
     * the timeout elapses. Production code never needs this - list()/has()
     * already degrade to empty/false on their own - it's for tests and any
     * future readiness/health check that needs a deterministic answer
     * instead of racing the background sync thread.
     */
    public boolean awaitReady(long timeoutMillis) throws InterruptedException {
        initialSyncDone.await(timeoutMillis, TimeUnit.MILLISECONDS);
        return ready;
    }

    @Override
    public String getName() {
        return "git:" + url + (root.equals(checkout) ? "" : "#" + checkout.relativize(root));
    }

    @Override
    public InputStream getStreamForName(String resName) {
        if (!ready) {
            throw new IllegalStateException("Git TEI repo " + url + " has not finished its initial sync yet");
        }
        try {
            return Files.newInputStream(resolveResource(resName), StandardOpenOption.READ);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read Git repo resource " + resName, e);
        }
    }

    @Override
    public boolean has(String resName) {
        if (!ready) {
            return false;
        }
        Path file = resolveResource(resName);
        return Files.isRegularFile(file) && matches(file);
    }

    @Override
    public File getFile(String resName) {
        if (!ready) {
            throw new IllegalStateException("Git TEI repo " + url + " has not finished its initial sync yet");
        }
        return resolveResource(resName).toFile();
    }

    @Override
    public List<String> list() {
        if (!ready) {
            return List.of();
        }
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
                // Shallow: this repo is only ever read for its current file
                // contents (see list()/has()/getStreamForName()), never for
                // history, and it's fetched read-only (never pushed to) - so
                // there's no reason to pull every past revision of every file
                // ever committed, which is exactly what made a nominally ~1GB
                // repo balloon to tens of GB on disk.
                runGit(checkout.getParent(), "clone", "--depth", "1", url, checkout.toString());
            }
        } catch (IOException e) {
            // A killed/interrupted clone or pull (see the timeout below) can leave
            // a half-populated .git behind. Left in place, the next attempt sees
            // ".git exists" and tries "pull" against that broken checkout instead
            // of cloning fresh - which can itself fail without ever completing,
            // compounding across restarts. (This is what produced a 35GB checkout
            // for what GitHub reports as a ~1GB repo: ~100 crash-loop restarts,
            // each adding to the same never-valid clone. See onboarding notes /
            // incident writeup for 2026-09-20.) Wipe it so the next attempt always
            // starts from a clean slate.
            deleteRecursively(checkout);
            throw new IllegalStateException("Cannot prepare Git repo " + url, e);
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort cleanup; a leftover file here just means the
                    // next sync attempt falls back to "pull" on it as before
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup, see above
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
            // Generous timeout: this always runs on the background sync thread
            // (see startSync()), never blocking app startup, so there's no
            // pressure to keep this short - a real multi-GB corpus deserves the
            // time to actually finish instead of getting killed mid-transfer.
            if (!process.waitFor(600, TimeUnit.SECONDS)) {
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
