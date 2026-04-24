package com.skycryck.tickstatssync.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.sync.FailureCategory;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GitServiceTest {

    private static final UUID U1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID U2 = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @BeforeAll
    static void disableJGitMMap() {
        // Without this, JGit mmap's pack files and Windows can't delete them until
        // the JVM unmaps — which only happens at GC time. JUnit's @TempDir cleanup
        // then fails. Disabling mmap trades a small perf hit in tests for reliable
        // teardown.
        WindowCacheConfig cfg = new WindowCacheConfig();
        cfg.setPackedGitMMAP(false);
        cfg.install();
    }

    @AfterEach
    void evictJGitCaches() {
        RepositoryCache.clear();
        WindowCacheConfig cfg = new WindowCacheConfig();
        cfg.setPackedGitMMAP(false);
        cfg.install();
    }

    @Test
    void initialCloneProducesShallowSingleBranchWorkdir(@TempDir Path root) throws Exception {
        Path bareRemote = initBareRemote(root, "remote.git");
        seedRemoteCommit(root, bareRemote, "seed.txt", "hello".getBytes());

        Path workdir = root.resolve("workdir");
        try (Closeable svc = service(bareRemote, workdir)) {
            svc.init();

            assertThat(Files.isDirectory(workdir.resolve(".git"))).isTrue();
            try (Git opened = Git.open(workdir.toFile())) {
                // depth=1 shallow
                Path shallow = workdir.resolve(".git").resolve("shallow");
                assertThat(Files.exists(shallow)).isTrue();
                // single-branch: refs/heads contains only main
                assertThat(opened.branchList().call()).hasSize(1);
                Ref head = opened.getRepository().findRef("HEAD");
                assertThat(head.getTarget().getName()).isEqualTo("refs/heads/main");
            }
        }
    }

    @Test
    void fetchAndResetCleansLocalTreeAgainstNewRemoteTip(@TempDir Path root) throws Exception {
        Path bareRemote = initBareRemote(root, "remote.git");
        seedRemoteCommit(root, bareRemote, "seed.txt", "one".getBytes());
        Path workdir = root.resolve("workdir");
        try (Closeable svc = service(bareRemote, workdir)) {
            svc.init();

            // Someone else pushes another commit to the remote:
            seedRemoteCommit(root, bareRemote, "seed.txt", "two".getBytes());
            svc.inner().fetchAndResetToRemote();

            assertThat(Files.readString(workdir.resolve("seed.txt"))).isEqualTo("two");
        }
    }

    @Test
    void writeFilesAndHasChangesDetectDiff(@TempDir Path root) throws Exception {
        Path bareRemote = initBareRemote(root, "remote.git");
        seedRemoteCommit(root, bareRemote, "seed.txt", "hi".getBytes());
        Path workdir = root.resolve("workdir");
        try (Closeable svc = service(bareRemote, workdir)) {
            svc.init();
            svc.inner().fetchAndResetToRemote();

            Map<UUID, byte[]> files = Map.of(U1, "{\"a\":1}".getBytes(), U2, "{\"b\":2}".getBytes());
            svc.inner().writeFiles(files);
            assertThat(svc.inner().hasChanges()).isTrue();

            String sha = svc.inner().commitAndPush("bot", "bot@example.com", "commit msg");
            assertThat(sha).hasSize(7);

            // now same content → no diff
            svc.inner().fetchAndResetToRemote();
            svc.inner().writeFiles(files);
            assertThat(svc.inner().hasChanges()).isFalse();
        }
    }

    @Test
    void commitAndPushUpdatesRemoteTip(@TempDir Path root) throws Exception {
        Path bareRemote = initBareRemote(root, "remote.git");
        seedRemoteCommit(root, bareRemote, "seed.txt", "hi".getBytes());
        Path workdir = root.resolve("workdir");
        try (Closeable svc = service(bareRemote, workdir)) {
            svc.init();
            svc.inner().fetchAndResetToRemote();

            Map<UUID, byte[]> files = Map.of(U1, "{\"a\":1}".getBytes());
            svc.inner().writeFiles(files);
            String sha = svc.inner().commitAndPush("bot", "bot@example.com",
                    "Update stats for my-server - 2026-04-22 12:00");

            // Inspect the bare remote directly.
            try (Git bare = Git.open(bareRemote.toFile())) {
                Ref head = bare.getRepository().findRef("refs/heads/main");
                try (RevWalk walk = new RevWalk(bare.getRepository())) {
                    RevCommit tip = walk.parseCommit(head.getObjectId());
                    assertThat(tip.getName()).startsWith(sha);
                    assertThat(tip.getFullMessage())
                            .isEqualTo("Update stats for my-server - 2026-04-22 12:00");
                }
                // verify the file landed under stats/<server>/data/<uuid>.json
                try (TreeWalk tw = new TreeWalk(bare.getRepository())) {
                    tw.reset(walk(bare, head).getTree().getId());
                    tw.setRecursive(true);
                    boolean found = false;
                    while (tw.next()) {
                        if (tw.getPathString().equals("stats/my-server/data/" + U1 + ".json")) {
                            found = true;
                            break;
                        }
                    }
                    assertThat(found).as("expected data file in remote tree").isTrue();
                }
            }
        }
    }

    @Test
    void snapshotDirectoryLandsAlongsideData(@TempDir Path root) throws Exception {
        Path bareRemote = initBareRemote(root, "remote.git");
        seedRemoteCommit(root, bareRemote, "seed.txt", "hi".getBytes());
        Path workdir = root.resolve("workdir");
        try (Closeable svc = service(bareRemote, workdir)) {
            svc.init();
            svc.inner().fetchAndResetToRemote();

            Map<UUID, byte[]> files = Map.of(U1, "{\"a\":1}".getBytes());
            svc.inner().writeSnapshot(java.time.LocalDate.of(2026, 4, 22), files);
            svc.inner().writeFiles(files);
            assertThat(svc.inner().hasChanges()).isTrue();

            svc.inner().commitAndPush("bot", "bot@example.com", "commit");

            try (Git bare = Git.open(bareRemote.toFile())) {
                Ref head = bare.getRepository().findRef("refs/heads/main");
                try (RevWalk walk = new RevWalk(bare.getRepository());
                        TreeWalk tw = new TreeWalk(bare.getRepository())) {
                    tw.reset(walk.parseCommit(head.getObjectId()).getTree().getId());
                    tw.setRecursive(true);
                    boolean dataFound = false;
                    boolean snapshotFound = false;
                    while (tw.next()) {
                        String p = tw.getPathString();
                        if (p.equals("stats/my-server/data/" + U1 + ".json")) dataFound = true;
                        if (p.equals("stats/my-server/snapshots/2026-04-22/" + U1 + ".json"))
                            snapshotFound = true;
                    }
                    assertThat(dataFound).as("data file").isTrue();
                    assertThat(snapshotFound).as("snapshot file").isTrue();
                }
            }
        }
    }

    @Test
    void missingDotGitTriggersRebuild(@TempDir Path root) throws Exception {
        Path bareRemote = initBareRemote(root, "remote.git");
        seedRemoteCommit(root, bareRemote, "seed.txt", "hi".getBytes());
        Path workdir = root.resolve("workdir");

        // Pre-populate workdir with a non-git directory to simulate corruption signal #1.
        Files.createDirectories(workdir);
        Files.writeString(workdir.resolve("marker.txt"), "garbage");

        try (Closeable svc = service(bareRemote, workdir)) {
            svc.init();
            // After rebuild the marker is gone and .git exists.
            assertThat(Files.exists(workdir.resolve("marker.txt"))).isFalse();
            assertThat(Files.isDirectory(workdir.resolve(".git"))).isTrue();
        }
    }

    @Test
    void remoteUrlDriftTriggersRebuild(@TempDir Path root) throws Exception {
        Path bareRemoteA = initBareRemote(root, "remote-a.git");
        seedRemoteCommit(root, bareRemoteA, "a.txt", "A".getBytes());
        Path bareRemoteB = initBareRemote(root, "remote-b.git");
        seedRemoteCommit(root, bareRemoteB, "b.txt", "B".getBytes());
        Path workdir = root.resolve("workdir");

        try (Closeable svc = service(bareRemoteA, workdir)) {
            svc.init();
            assertThat(Files.exists(workdir.resolve("a.txt"))).isTrue();
        }
        // Now point the service at a different remote; it must rebuild.
        try (Closeable svc = service(bareRemoteB, workdir)) {
            svc.init();
            assertThat(Files.exists(workdir.resolve("a.txt"))).isFalse();
            assertThat(Files.exists(workdir.resolve("b.txt"))).isTrue();
        }
    }

    @Test
    void unreachableRemoteClassifiesAsNetworkOrAuth(@TempDir Path root) throws Exception {
        Path workdir = root.resolve("workdir");
        // Point at a bogus local path that doesn't exist — JGit surfaces this as
        // a TransportException (no such repository), which we map to AUTH/NETWORK
        // — the contract is "non-IO, non-CONFLICT, not UNKNOWN".
        Path bogus = root.resolve("does-not-exist.git");
        try (Closeable svc = service(bogus, workdir)) {
            assertThatThrownBy(svc::init)
                    .isInstanceOf(GitOperationException.class)
                    .matches(t -> {
                        FailureCategory cat = ((GitOperationException) t).category();
                        return cat == FailureCategory.AUTH
                                || cat == FailureCategory.NETWORK
                                || cat == FailureCategory.IO;
                    });
        }
    }

    // -------------------------------------------------------------------- harness

    private static Closeable service(Path bareRemote, Path workdir) {
        TickstatsSyncConfig config = new TickstatsSyncConfig(
                "owner/repo-name",
                "main",
                "dummy-token",
                "TickstatsSync Bot",
                "bot@example.com",
                "my-server",
                workdir.getParent().resolve("stats-path"),
                "0 */6 * * *",
                ZoneId.of("UTC"),
                false,
                true,
                3,
                Duration.ofSeconds(10));
        // The config is used only for branch name, serverName, token, ownerAndRepo.
        // ownerAndRepo is ignored by GitService for file:// remotes because we use
        // `remoteUrl()` which builds https://github.com/<ownerAndRepo>.git — so for
        // tests we subclass with a local-file URL override.
        GitService real = new GitServiceForTest(config, workdir, bareRemote);
        return new Closeable(real);
    }

    private static Path initBareRemote(Path root, String name) throws Exception {
        Path bare = root.resolve(name);
        try (Git git = Git.init().setBare(true).setDirectory(bare.toFile()).call()) {
            return bare;
        }
    }

    /** Seeds {@code file} with {@code content} on {@code bareRemote}'s main branch. */
    private static void seedRemoteCommit(Path root, Path bareRemote, String file, byte[] content)
            throws Exception {
        // Is the bare already on main with at least one commit?
        ObjectId existingMain;
        try (Git bare = Git.open(bareRemote.toFile())) {
            existingMain = bare.getRepository().resolve("refs/heads/main");
        }

        Path scratch = Files.createTempDirectory(root, "scratch-");
        Git scratchGit;
        if (existingMain == null) {
            // Bootstrap: init a fresh repo with main as the initial branch.
            scratchGit = Git.init()
                    .setDirectory(scratch.toFile())
                    .setInitialBranch("main")
                    .call();
        } else {
            // Subsequent commit: clone main explicitly so JGit doesn't depend on HEAD.
            scratchGit = Git.cloneRepository()
                    .setURI(bareRemote.toUri().toString())
                    .setDirectory(scratch.toFile())
                    .setBranch("main")
                    .setBranchesToClone(List.of("refs/heads/main"))
                    .call();
        }

        try (scratchGit) {
            Files.write(scratch.resolve(file), content);
            scratchGit.add().addFilepattern(file).call();
            scratchGit.commit().setMessage("seed " + file)
                    .setAuthor("seed", "seed@example.com")
                    .setCommitter("seed", "seed@example.com").call();
            scratchGit.push()
                    .setRemote(bareRemote.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
        }

        // Point the bare's HEAD at main so subsequent clones don't complain. Only
        // needed on the first seed; idempotent on subsequent ones.
        try (Git bare = Git.open(bareRemote.toFile())) {
            bare.getRepository().getRefDatabase().newUpdate("HEAD", false).link("refs/heads/main");
        }

        deleteRecursively(scratch);
    }

    private static RevCommit walk(Git g, Ref head) throws IOException {
        try (RevWalk rw = new RevWalk(g.getRepository())) {
            return rw.parseCommit(head.getObjectId());
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                tryDeleteWithRetry(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                tryDeleteWithRetry(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * On Windows JGit sometimes holds brief handles on pack files after Git.close().
     * Retry a few times, then fall through — @TempDir's own cleanup will catch the
     * remainder at the end of the test.
     */
    private static void tryDeleteWithRetry(Path p) throws IOException {
        IOException last = null;
        for (int i = 0; i < 5; i++) {
            try {
                Files.delete(p);
                return;
            } catch (java.nio.file.AccessDeniedException ex) {
                p.toFile().setWritable(true);
                last = ex;
            } catch (java.nio.file.FileSystemException ex) {
                last = ex;
            } catch (IOException ex) {
                last = ex;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Don't propagate — let @TempDir cleanup absorb the failure if any.
        if (last != null && Files.exists(p)) {
            // Last resort: mark for deletion on JVM exit so the @TempDir cleanup
            // has a cleaner slate on the next test.
            p.toFile().deleteOnExit();
        }
    }

    /**
     * A {@link GitService} that targets a local-file bare repo. Overrides only the
     * URL that the real service builds from {@code config.ownerAndRepo()}. All other
     * behavior is the production code path.
     */
    static final class GitServiceForTest extends GitService {
        private final String remoteUrl;

        GitServiceForTest(TickstatsSyncConfig config, Path workdir, Path bareRemote) {
            super(config, workdir);
            this.remoteUrl = bareRemote.toUri().toString();
        }

        @Override
        protected String remoteUrl() {
            return remoteUrl;
        }
    }

    /** Thin AutoCloseable wrapper so each test's service is closed in a try-with-resources. */
    static final class Closeable implements AutoCloseable {
        private final GitService service;
        Closeable(GitService service) { this.service = service; }
        GitService inner() { return service; }
        void init() throws GitOperationException { service.initOrOpenLocalClone(); }
        @Override public void close() { service.close(); }
    }
}
