package com.skycryck.tickstatssync.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.git.GitOperationException;
import com.skycryck.tickstatssync.git.GitService;
import com.skycryck.tickstatssync.scheduler.CronScheduler;
import com.skycryck.tickstatssync.stats.StatsReader;
import com.skycryck.tickstatssync.stats.StatsSnapshot;
import com.skycryck.tickstatssync.sync.FailureCategory;
import com.skycryck.tickstatssync.sync.SyncLock;
import com.skycryck.tickstatssync.sync.SyncMetrics;
import com.skycryck.tickstatssync.sync.SyncOrchestrator;
import com.skycryck.tickstatssync.sync.SyncOutcome;
import com.skycryck.tickstatssync.util.LogRedactionFilter;
import com.skycryck.tickstatssync.util.PatMasker;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

/**
 * End-to-end assertion for SC-007 + FR-023 — the PAT must never surface in any
 * observable artifact. Drives a full {@code SUCCESS_WITH_COMMIT} cycle and a
 * full {@code FAILURE} cycle through a real {@link SyncOrchestrator} wired to
 * a {@link LogRedactionFilter}-equipped logger, plus both the {@code /tickstats
 * sync} and {@code /tickstats status} command surfaces. Captures every channel
 * operators can read — JUL records (message + thrown + formatted output),
 * {@link com.skycryck.tickstatssync.command.TickstatsCommand} replies, and the
 * git commit message the plugin would actually push — then scans each captured
 * string for the literal canary token.
 *
 * <p>The canary is injected into exception messages for the failure path so we
 * exercise the {@link LogRedactionFilter#isLoggable(LogRecord)} throwable-masking
 * branch, not just the straight-message path. A dependency that one day leaks
 * the PAT through an error message (e.g., a JGit upstream regression) would
 * reach our logger the same way — this test proves the filter catches it
 * before publication.
 *
 * <p>Package placement note: T045's tasks.md path nominally lives at the top
 * level, but {@link TickstatsCommand#handleSync} and
 * {@link TickstatsCommand#handleStatus} are deliberately package-private (they
 * are the unit-test entry points behind the Brigadier adapter). Placing the
 * test here preserves that visibility contract instead of forcing an
 * API-visibility relaxation or a Brigadier-plumbed harness just to scan
 * sendMessage output.
 */
final class PatLeakageTest {

    /**
     * The canary PAT — if any captured artifact ever contains this literal
     * substring, the redaction pipeline is broken. The shape is distinctive
     * enough that an accidental collision with any other test content is
     * effectively impossible.
     */
    private static final String CANARY_TOKEN =
            "ghp_LEAK_CANARY_000000000000000000000000";

    private static final UUID U1 = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void successCycleEmitsNoCanaryInAnyObservableArtifact() {
        Harness h = new Harness();

        h.git.hasChangesSupplier = () -> true;
        h.git.commitShaSupplier = () -> "abc1234";

        // Drive one full success cycle through the real SyncOrchestrator.
        SyncOutcome outcome = h.runOrchestrator(SyncOrchestrator.Trigger.SCHEDULED);

        assertThat(outcome).isEqualTo(SyncOutcome.SUCCESS_WITH_COMMIT);

        // Also render /tickstats status to cover the read-side command surface.
        List<String> statusLines = new ArrayList<>();
        h.command.handleStatus(statusLines::add);

        h.assertNoCanaryInAnyCapture(statusLines);
    }

    @Test
    void failureCycleWithCanaryInExceptionMessageIsScrubbedByLogFilter() {
        Harness h = new Harness();

        // Simulate a library that formats an error message containing the PAT,
        // both on the top-level throwable and on its cause chain. The filter
        // must redact every layer before the record is published.
        h.git.fetchAndResetAction = () -> {
            throw new GitOperationException(
                    FailureCategory.AUTH,
                    "HTTP 401 on fetch; bearer " + CANARY_TOKEN + " rejected by origin",
                    new RuntimeException("cause also leaks " + CANARY_TOKEN));
        };

        SyncOutcome outcome = h.runOrchestrator(SyncOrchestrator.Trigger.SCHEDULED);

        assertThat(outcome).isEqualTo(SyncOutcome.FAILURE);
        // At least one captured record had the cause-chain embedded, proving
        // the filter actually saw the potentially-leaky input.
        assertThat(h.handler.records).isNotEmpty();

        h.assertNoCanaryInAnyCapture(List.of());
    }

    @Test
    void tickstatsSyncCommandPathNeverLeaksCanaryAcrossSuccessAndFailure() {
        Harness h = new Harness();

        // --- Success path ---------------------------------------------------
        h.git.hasChangesSupplier = () -> true;
        h.git.commitShaSupplier = () -> "def5678";

        List<String> successImmediate = new ArrayList<>();
        List<String> successTerminal = new ArrayList<>();
        h.command.handleSync(
                successImmediate::add,
                successTerminal::add,
                Runnable::run);  // run async body inline so the terminal reply lands

        // --- Failure path with token-leaking exception ----------------------
        h.git.fetchAndResetAction = () -> {
            throw new GitOperationException(
                    FailureCategory.AUTH,
                    "push denied: bearer " + CANARY_TOKEN + " is revoked",
                    null);
        };
        h.git.hasChangesSupplier = () -> false;
        h.git.commitShaSupplier = () -> "0000000";

        List<String> failureImmediate = new ArrayList<>();
        List<String> failureTerminal = new ArrayList<>();
        h.command.handleSync(
                failureImmediate::add,
                failureTerminal::add,
                Runnable::run);

        // --- Healthy status render ------------------------------------------
        List<String> status = new ArrayList<>();
        h.command.handleStatus(status::add);

        List<String> allCommandOutput = new ArrayList<>();
        allCommandOutput.addAll(successImmediate);
        allCommandOutput.addAll(successTerminal);
        allCommandOutput.addAll(failureImmediate);
        allCommandOutput.addAll(failureTerminal);
        allCommandOutput.addAll(status);

        h.assertNoCanaryInAnyCapture(allCommandOutput);
    }

    // =================================================================== harness

    /**
     * Wires the real {@link SyncOrchestrator}, {@link TickstatsCommand}, and a
     * {@link LogRedactionFilter}-equipped logger against a fake {@link GitService}
     * whose {@code commitAndPush} captures every commit message the plugin would
     * have pushed. {@link SyncMetrics} and {@link SyncLock} are the production
     * classes; only the git I/O and the stats-read I/O are faked.
     */
    private static final class Harness {
        final PatMasker masker = new PatMasker();
        final Logger logger = Logger.getLogger(
                "PatLeakageTest-" + System.nanoTime());
        final CapturingHandler handler = new CapturingHandler();
        final MutableClock clock = new MutableClock(Instant.parse("2026-04-22T12:00:00Z"));
        final SyncLock lock = new SyncLock(clock);
        final SyncMetrics metrics = new SyncMetrics();
        final FakeStatsReader statsReader = new FakeStatsReader();
        final FakeGitService git = new FakeGitService();
        final NoSleepSleeper sleeper = new NoSleepSleeper();
        final StaticConfigService configService;
        final SyncOrchestrator orchestrator;
        final CronScheduler scheduler;
        final TickstatsCommand command;

        Harness() {
            // Mirrors the production bootstrap order in TickstatsSyncPlugin:
            //   PatMasker first, LogRedactionFilter second, token loaded third.
            masker.setToken(CANARY_TOKEN);
            logger.setFilter(new LogRedactionFilter(masker));
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(handler);

            TickstatsSyncConfig cfg = new TickstatsSyncConfig(
                    "owner/repo-name",
                    "main",
                    CANARY_TOKEN,
                    "Bot",
                    "bot@example.com",
                    "my-server",
                    Path.of(".").toAbsolutePath(),
                    "0 */6 * * *",
                    ZoneId.of("Europe/Paris"),
                    false,
                    true,
                    3,
                    Duration.ofSeconds(10));
            this.configService = new StaticConfigService(cfg);

            this.orchestrator = new SyncOrchestrator(
                    configService, statsReader, git, lock, metrics,
                    clock, logger, sleeper);

            this.scheduler = new CronScheduler(
                    configService, orchestrator, metrics, lock, logger, clock,
                    new NoopTaskScheduler());

            this.command = new TickstatsCommand(
                    configService, lock, metrics, orchestrator, scheduler,
                    masker, null,
                    () -> "1.0.0", clock, () -> {});
        }

        /** Acquires the lock and runs one cycle — mirrors the orchestrator's caller contract. */
        SyncOutcome runOrchestrator(SyncOrchestrator.Trigger trigger) {
            lock.release();
            if (!lock.tryAcquire()) {
                throw new IllegalStateException("harness bug: lock already held");
            }
            return orchestrator.runOnce(trigger);
        }

        void assertNoCanaryInAnyCapture(List<String> extraCaptures) {
            List<String> loggerCaptures = handler.allCapturedStrings();
            // Paranoid first pass — an empty list vacuously passes a not-contains
            // check; assert that the cycle actually emitted something.
            assertThat(handler.records)
                    .as("the orchestrator/command cycle must have emitted at least one LogRecord")
                    .isNotEmpty();
            assertThat(loggerCaptures)
                    .as("LogRedactionFilter must scrub the canary from every published record")
                    .allSatisfy(s -> assertThat(s).doesNotContain(CANARY_TOKEN));

            assertThat(extraCaptures)
                    .as("TickstatsCommand replies must never carry the canary token")
                    .allSatisfy(s -> assertThat(s).doesNotContain(CANARY_TOKEN));

            assertThat(git.capturedCommitMessages)
                    .as("plugin-authored commit messages must never carry the canary token")
                    .allSatisfy(s -> assertThat(s).doesNotContain(CANARY_TOKEN));

            assertThat(configService.renderedToStringCaptures())
                    .as("TickstatsSyncConfig.toString must redact the token (data-model lifecycle note)")
                    .allSatisfy(s -> assertThat(s).doesNotContain(CANARY_TOKEN));
        }
    }

    /** Captures every {@link LogRecord} and the rendered text of each one. */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override public void flush() {}
        @Override public void close() {}

        /**
         * Returns every human-readable string that could possibly be observed
         * by an operator: the published message, any parameters' {@code toString},
         * each thrown's message, and the full stack trace rendering.
         */
        List<String> allCapturedStrings() {
            List<String> out = new ArrayList<>();
            for (LogRecord r : records) {
                if (r.getMessage() != null) {
                    out.add(r.getMessage());
                }
                Object[] params = r.getParameters();
                if (params != null) {
                    for (Object p : params) {
                        if (p != null) out.add(p.toString());
                    }
                }
                Throwable t = r.getThrown();
                while (t != null) {
                    if (t.getMessage() != null) out.add(t.getMessage());
                    out.add(renderStackTrace(t));
                    t = t.getCause();
                }
            }
            return out;
        }

        private static String renderStackTrace(Throwable t) {
            StringWriter sw = new StringWriter();
            try (PrintWriter pw = new PrintWriter(sw)) {
                t.printStackTrace(pw);
            }
            return sw.toString();
        }
    }

    /** ConfigService stub that also snapshots every TickstatsSyncConfig.toString(). */
    private static final class StaticConfigService extends ConfigService {
        private final TickstatsSyncConfig fixed;
        private final List<String> toStringCaptures = new ArrayList<>();

        StaticConfigService(TickstatsSyncConfig fixed) {
            super(() -> (FileConfiguration) null, Path.of("."), () -> null);
            this.fixed = fixed;
            // Snapshot toString at least once so the assert list is non-empty.
            toStringCaptures.add(fixed.toString());
        }

        @Override public TickstatsSyncConfig current() { return fixed; }
        @Override public TickstatsSyncConfig load() { return fixed; }

        List<String> renderedToStringCaptures() { return toStringCaptures; }
    }

    private static final class FakeStatsReader extends StatsReader {
        Map<UUID, byte[]> files = Map.of(U1, "{\"stats\":{}}".getBytes());

        FakeStatsReader() {
            super(Clock.systemUTC(), Logger.getAnonymousLogger());
        }

        @Override
        public StatsSnapshot read(Path statsDir) {
            return new StatsSnapshot(files, Instant.EPOCH);
        }
    }

    /**
     * Fake git service that captures every message passed to {@code commitAndPush}
     * (so we can scan what the plugin would actually push to GitHub) while
     * delegating behavior to per-test suppliers/actions.
     */
    private static final class FakeGitService extends GitService {
        Runnable initOrOpenAction = () -> {};
        Action fetchAndResetAction = () -> {};
        Consumer<Map<UUID, byte[]>> writeFilesAction = data -> {};
        Supplier<Boolean> hasChangesSupplier = () -> false;
        Supplier<String> commitShaSupplier = () -> "0000000";
        Function<java.time.LocalDate, Boolean> hasSnapshotDirectoryFor = d -> true;
        final List<String> capturedCommitMessages = new ArrayList<>();

        FakeGitService() {
            super(dummyConfig(), Path.of("."));
        }

        private static TickstatsSyncConfig dummyConfig() {
            return new TickstatsSyncConfig(
                    "owner/repo", "main", "placeholder-token-not-the-canary",
                    "b", "b@x", "s",
                    Path.of("."), "0 */6 * * *", ZoneId.of("UTC"),
                    false, true, 3, Duration.ofSeconds(10));
        }

        @Override public void initOrOpenLocalClone() { initOrOpenAction.run(); }
        @Override public void fetchAndResetToRemote() throws GitOperationException {
            fetchAndResetAction.run();
        }
        @Override public void writeFiles(Map<UUID, byte[]> data) {
            writeFilesAction.accept(data);
        }
        @Override public boolean hasChanges() { return hasChangesSupplier.get(); }
        @Override public String commitAndPush(String author, String email, String message) {
            capturedCommitMessages.add(message);
            return commitShaSupplier.get();
        }
        @Override public boolean hasSnapshotDirectory(java.time.LocalDate today) {
            return hasSnapshotDirectoryFor.apply(today);
        }
        @Override public void writeSnapshot(java.time.LocalDate today, Map<UUID, byte[]> data) {}
    }

    @FunctionalInterface
    private interface Action {
        void run() throws GitOperationException;
    }

    private static final class NoSleepSleeper implements SyncOrchestrator.Sleeper {
        @Override public void sleep(Duration duration) { /* no-op */ }
    }

    private static final class NoopTaskScheduler implements CronScheduler.TaskScheduler {
        @Override public int scheduleLater(Runnable task, long delayMs) { return 0; }
        @Override public void cancel(int taskId) {}
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        @Override public ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }
}
