package com.skycryck.tickstatssync.git;

import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.sync.FailureCategory;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

public class GitService {

    /**
     * GitHub accepts any non-empty username for HTTPS basic auth with a PAT.
     * {@code x-access-token} is the idiomatic placeholder for fine-grained PATs and
     * works with classic PATs as well.
     */
    private static final String PAT_USERNAME = "x-access-token";

    private final TickstatsSyncConfig config;
    private final Path workdir;
    private Git git;

    public GitService(TickstatsSyncConfig config, Path workdir) {
        this.config = config;
        this.workdir = workdir;
    }

    // ------------------------------------------------------------------ lifecycle

    public void initOrOpenLocalClone() throws GitOperationException {
        // R4 corruption detection: if anything about the existing workdir looks off,
        // wipe and re-clone. We classify any other fetch/push failure as a normal
        // transient failure later — not here.
        if (Files.exists(workdir)) {
            if (!isWorkdirHealthy()) {
                close();
                try {
                    deleteRecursively(workdir);
                } catch (IOException ex) {
                    throw new GitOperationException(FailureCategory.IO,
                            "failed to remove corrupt workdir at " + workdir, ex);
                }
            }
        }

        if (git == null) {
            if (Files.isDirectory(workdir.resolve(".git"))) {
                try {
                    git = Git.open(workdir.toFile());
                } catch (IOException ex) {
                    throw new GitOperationException(FailureCategory.IO,
                            "failed to open existing workdir at " + workdir, ex);
                }
            } else {
                freshClone();
            }
        }
    }

    private void freshClone() throws GitOperationException {
        try {
            Files.createDirectories(workdir.getParent());
        } catch (IOException ex) {
            throw new GitOperationException(FailureCategory.IO,
                    "failed to create workdir parent: " + ex.getMessage(), ex);
        }
        try {
            git = Git.cloneRepository()
                    .setURI(remoteUrl())
                    .setDirectory(workdir.toFile())
                    .setDepth(1)
                    .setBranch(config.branch())
                    .setBranchesToClone(List.of("refs/heads/" + config.branch()))
                    .setCredentialsProvider(credentials())
                    .call();
        } catch (GitAPIException ex) {
            throw new GitOperationException(categorize(ex), "clone failed: " + ex.getMessage(), ex);
        }
    }

    private boolean isWorkdirHealthy() {
        Path dotGit = workdir.resolve(".git");
        if (!Files.isDirectory(dotGit)) {
            return false;
        }
        if (!Files.exists(dotGit.resolve("HEAD")) || !Files.exists(dotGit.resolve("config"))) {
            return false;
        }
        try (Git test = Git.open(workdir.toFile())) {
            ObjectId head = test.getRepository().resolve("HEAD");
            if (head == null) {
                return false;
            }
            String configuredUrl = test.getRepository().getConfig()
                    .getString("remote", "origin", "url");
            if (configuredUrl == null || !configuredUrl.equals(remoteUrl())) {
                return false;
            }
            // A structurally unreadable index / working tree will throw here.
            test.status().call();
            return true;
        } catch (IOException | GitAPIException | RuntimeException ex) {
            return false;
        }
    }

    public void close() {
        if (git != null) {
            git.close();
            git = null;
        }
    }

    // ------------------------------------------------------------------ git ops

    public void fetchAndResetToRemote() throws GitOperationException {
        ensureOpen();
        try {
            git.fetch()
                    .setRemote("origin")
                    .setRefSpecs("+refs/heads/" + config.branch() + ":refs/remotes/origin/" + config.branch())
                    .setCredentialsProvider(credentials())
                    .call();
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("refs/remotes/origin/" + config.branch())
                    .call();
            git.clean().setForce(true).setCleanDirectories(true).call();
        } catch (GitAPIException ex) {
            throw new GitOperationException(categorize(ex),
                    "fetch/reset failed: " + ex.getMessage(), ex);
        }
    }

    public void writeFiles(Map<UUID, byte[]> data) throws GitOperationException {
        Path dataDir = workdir.resolve("stats").resolve(config.serverName()).resolve("data");
        try {
            if (Files.isDirectory(dataDir)) {
                // Truncate to exactly the current UUID set — removes stale UUIDs.
                Set<String> keep = new HashSet<>();
                for (UUID uuid : data.keySet()) {
                    keep.add(uuid + ".json");
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir)) {
                    for (Path existing : stream) {
                        if (Files.isRegularFile(existing)
                                && !keep.contains(existing.getFileName().toString())) {
                            Files.delete(existing);
                        }
                    }
                }
            } else {
                Files.createDirectories(dataDir);
            }
            for (Map.Entry<UUID, byte[]> entry : data.entrySet()) {
                Path target = dataDir.resolve(entry.getKey() + ".json");
                Files.write(target, entry.getValue());
            }
        } catch (IOException ex) {
            throw new GitOperationException(FailureCategory.IO,
                    "failed to write stats files to " + dataDir + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * First-write-wins detection per research §R5. Returns true iff the working
     * copy (which is up-to-date with remote after {@link #fetchAndResetToRemote()})
     * already contains {@code stats/<server>/snapshots/<today>/}.
     */
    public boolean hasSnapshotDirectory(LocalDate today) {
        Path snapshotDir = workdir.resolve("stats")
                .resolve(config.serverName())
                .resolve("snapshots")
                .resolve(today.toString());
        return Files.isDirectory(snapshotDir);
    }

    public void writeSnapshot(LocalDate today, Map<UUID, byte[]> data) throws GitOperationException {
        Path snapshotDir = workdir.resolve("stats")
                .resolve(config.serverName())
                .resolve("snapshots")
                .resolve(today.toString());
        try {
            Files.createDirectories(snapshotDir);
            for (Map.Entry<UUID, byte[]> entry : data.entrySet()) {
                Path target = snapshotDir.resolve(entry.getKey() + ".json");
                Files.write(target, entry.getValue());
            }
        } catch (IOException ex) {
            throw new GitOperationException(FailureCategory.IO,
                    "failed to write snapshot files to " + snapshotDir + ": " + ex.getMessage(), ex);
        }
    }

    public boolean hasChanges() throws GitOperationException {
        ensureOpen();
        try {
            Status status = git.status().addPath("stats").call();
            return !status.isClean();
        } catch (GitAPIException ex) {
            throw new GitOperationException(FailureCategory.IO,
                    "git status failed: " + ex.getMessage(), ex);
        }
    }

    public String commitAndPush(String author, String email, String message)
            throws GitOperationException {
        ensureOpen();
        try {
            // Stage additions and modifications.
            git.add().addFilepattern("stats").call();
            // Handle deletions: git add doesn't stage them; iterate the missing list and rm.
            Status status = git.status().addPath("stats").call();
            List<String> missing = new ArrayList<>(status.getMissing());
            if (!missing.isEmpty()) {
                var rm = git.rm();
                for (String path : missing) {
                    rm.addFilepattern(path);
                }
                rm.call();
            }

            PersonIdent ident = new PersonIdent(author, email);
            RevCommit commit = git.commit()
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setMessage(message)
                    .call();

            Iterable<PushResult> results = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                            "refs/heads/" + config.branch() + ":refs/heads/" + config.branch()))
                    .setCredentialsProvider(credentials())
                    .call();

            for (PushResult r : results) {
                for (RemoteRefUpdate u : r.getRemoteUpdates()) {
                    switch (u.getStatus()) {
                        case OK, UP_TO_DATE -> { /* ok */ }
                        case REJECTED_NONFASTFORWARD, REJECTED_NODELETE, REJECTED_REMOTE_CHANGED ->
                                throw new GitOperationException(FailureCategory.CONFLICT,
                                        "push rejected: " + u.getStatus() + " "
                                                + safeMessage(u.getMessage()));
                        case REJECTED_OTHER_REASON, NON_EXISTING, AWAITING_REPORT, NOT_ATTEMPTED ->
                                throw new GitOperationException(FailureCategory.UNKNOWN,
                                        "push not completed: " + u.getStatus() + " "
                                                + safeMessage(u.getMessage()));
                    }
                }
            }

            return commit.getName().substring(0, 7);
        } catch (GitAPIException ex) {
            throw new GitOperationException(categorize(ex),
                    "commit/push failed: " + ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void ensureOpen() throws GitOperationException {
        if (git == null) {
            throw new GitOperationException(FailureCategory.IO,
                    "GitService not initialized; call initOrOpenLocalClone() first");
        }
    }

    private UsernamePasswordCredentialsProvider credentials() {
        return new UsernamePasswordCredentialsProvider(PAT_USERNAME, config.token());
    }

    /** Overridable so tests can point at a local {@code file://} bare repository. */
    protected String remoteUrl() {
        return "https://github.com/" + config.ownerAndRepo() + ".git";
    }

    private static String safeMessage(String m) {
        return m == null ? "" : m;
    }

    /** Maps a JGit exception to a FailureCategory using class + message heuristics. */
    static FailureCategory categorize(Throwable cause) {
        Throwable t = cause;
        while (t != null) {
            if (t instanceof TransportException) {
                String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
                if (m.contains("401") || m.contains("403")
                        || m.contains("not authorized") || m.contains("authentication")
                        || m.contains("forbidden")) {
                    return FailureCategory.AUTH;
                }
                if (m.contains("unknown host") || m.contains("connect timed out")
                        || m.contains("connection refused") || m.contains("network")
                        || m.contains("timeout") || m.contains("resolve")) {
                    return FailureCategory.NETWORK;
                }
                // Default transport failures to NETWORK.
                return FailureCategory.NETWORK;
            }
            if (t instanceof InvalidRemoteException || t instanceof RefNotFoundException) {
                return FailureCategory.AUTH;
            }
            if (t instanceof CheckoutConflictException) {
                return FailureCategory.CONFLICT;
            }
            if (t instanceof RepositoryNotFoundException || t instanceof CorruptObjectException) {
                return FailureCategory.IO;
            }
            if (t instanceof java.net.UnknownHostException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ConnectException) {
                return FailureCategory.NETWORK;
            }
            if (t instanceof IOException) {
                return FailureCategory.IO;
            }
            t = t.getCause();
        }
        return FailureCategory.UNKNOWN;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                try {
                    Files.delete(file);
                } catch (java.nio.file.AccessDeniedException ex) {
                    // JGit can leave pack files read-only on Windows.
                    file.toFile().setWritable(true);
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // Exposed for tests that need to force a deterministic visit order.
    @SuppressWarnings("unused")
    static Comparator<Path> byName() {
        return Comparator.comparing(p -> p.getFileName().toString());
    }
}
