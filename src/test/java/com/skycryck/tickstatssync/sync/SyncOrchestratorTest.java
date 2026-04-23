package com.skycryck.tickstatssync.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.git.GitOperationException;
import com.skycryck.tickstatssync.git.GitService;
import com.skycryck.tickstatssync.stats.StatsReader;
import com.skycryck.tickstatssync.stats.StatsSnapshot;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

final class SyncOrchestratorTest {

    private static final UUID U1 = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void cleanStateScheduledYieldsSuccessNoChangesAndLogsR15Line() {
        Harness h = new Harness();
        h.git.hasChanges = () -> false;

        SyncOutcome outcome = h.runOnce(SyncOrchestrator.Trigger.SCHEDULED);

        assertThat(outcome).isEqualTo(SyncOutcome.SUCCESS_NO_CHANGES);
        assertThat(h.capturedMessages())
                .anyMatch(m -> m.contains("outcome=SUCCESS_NO_CHANGES")
                        && m.contains("trigger=SCHEDULED")
                        && m.contains("attempts=1")
                        && m.contains("commit=none")
                        && m.contains("category=none"));
        assertThat(h.metrics.lastTrigger()).isEqualTo(SyncOrchestrator.Trigger.SCHEDULED);
        assertThat(h.metrics.lastReachability()).isEqualTo(SyncMetrics.Reachability.OK);
    }

    @Test
    void changedStatsYieldSuccessWithCommit7CharSha() {
        Harness h = new Harness();
        h.git.hasChanges = () -> true;
        h.git.commitAndPush = () -> "abc1234";

        SyncOutcome outcome = h.runOnce(SyncOrchestrator.Trigger.SCHEDULED);

        assertThat(outcome).isEqualTo(SyncOutcome.SUCCESS_WITH_COMMIT);
        assertThat(h.capturedMessages())
                .anyMatch(m -> m.contains("outcome=SUCCESS_WITH_COMMIT")
                        && m.contains("trigger=SCHEDULED")
                        && m.contains("commit=abc1234")
                        && m.contains("attempts=1"));
    }

    @Test
    void transientRetrySucceedsOnThirdAttemptWithExponentialBackoff() {
        Harness h = new Harness();
        int[] fetchCount = {0};
        h.git.fetchAndResetAction = () -> {
            fetchCount[0]++;
            if (fetchCount[0] < 3) {
                throw new GitOperationException(FailureCategory.NETWORK,
                        "simulated transient network", null);
            }
        };
        h.git.hasChanges = () -> true;
        h.git.commitAndPush = () -> "def5678";

        SyncOutcome outcome = h.runOnce(SyncOrchestrator.Trigger.SCHEDULED);

        assertThat(outcome).isEqualTo(SyncOutcome.SUCCESS_WITH_COMMIT);
        assertThat(h.sleeper.sleeps)
                .as("exponential backoff: first attempt fails → sleep 10s; second fails → 20s")
                .containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(20));
        assertThat(h.capturedMessages())
                .anyMatch(m -> m.contains("outcome=SUCCESS_WITH_COMMIT")
                        && m.contains("attempts=3"));
    }

    @Test
    void authFailureAbandonsImmediatelyWithNamedWarning() {
        Harness h = new Harness();
        h.git.fetchAndResetAction = () -> {
            throw new GitOperationException(FailureCategory.AUTH, "HTTP 401 Unauthorized", null);
        };

        SyncOutcome outcome = h.runOnce(SyncOrchestrator.Trigger.SCHEDULED);

        assertThat(outcome).isEqualTo(SyncOutcome.FAILURE);
        assertThat(h.sleeper.sleeps).isEmpty();
        assertThat(h.capturedMessages())
                .anyMatch(m -> m.contains(
                        "giving up immediately - non-transient failure, check your PAT and repo configuration"));
        assertThat(h.capturedMessages())
                .anyMatch(m -> m.contains("outcome=FAILURE")
                        && m.contains("trigger=SCHEDULED")
                        && m.contains("category=AUTH")
                        && m.contains("attempts=1"));
        assertThat(h.metrics.lastFailureCategory()).isEqualTo(FailureCategory.AUTH);
        assertThat(h.metrics.lastReachability()).isEqualTo(SyncMetrics.Reachability.FAILED);
    }

    @Test
    void transientExhaustionEmitsFailureWithThreeAttempts() {
        Harness h = new Harness();
        h.git.fetchAndResetAction = () -> {
            throw new GitOperationException(FailureCategory.NETWORK, "timeout", null);
        };

        SyncOutcome outcome = h.runOnce(SyncOrchestrator.Trigger.SCHEDULED);

        assertThat(outcome).isEqualTo(SyncOutcome.FAILURE);
        assertThat(h.capturedMessages())
                .anyMatch(m -> m.contains("outcome=FAILURE")
                        && m.contains("category=NETWORK")
                        && m.contains("attempts=3"));
        // Only backoff between attempts 1→2 and 2→3 (not after attempt 3).
        assertThat(h.sleeper.sleeps).hasSize(2);
    }

    @Test
    void triggerPlumbingWritesLastTriggerAndLogsCorrectTrigger() {
        Harness h = new Harness();
        h.git.hasChanges = () -> false;

        h.runOnce(SyncOrchestrator.Trigger.MANUAL);
        assertThat(h.metrics.lastTrigger()).isEqualTo(SyncOrchestrator.Trigger.MANUAL);
        assertThat(h.capturedMessages()).anyMatch(m -> m.contains("trigger=MANUAL"));

        h.capturedMessages.clear();
        h.runOnce(SyncOrchestrator.Trigger.STARTUP);
        assertThat(h.metrics.lastTrigger()).isEqualTo(SyncOrchestrator.Trigger.STARTUP);
        assertThat(h.capturedMessages()).anyMatch(m -> m.contains("trigger=STARTUP"));
    }

    @Test
    void syncLockAlwaysReleasedEvenOnUncheckedException() {
        Harness h = new Harness();
        h.git.initOrOpenLocalCloneAction = () -> {
            throw new IllegalStateException("plugin bug");
        };

        SyncOutcome outcome = h.runOnce(SyncOrchestrator.Trigger.SCHEDULED);

        assertThat(outcome).isEqualTo(SyncOutcome.FAILURE);
        assertThat(h.lock.heldSince())
                .as("runOnce's finally block must release even on unchecked exceptions")
                .isEmpty();
    }

    @Test
    void ioFailureBeforeRemoteTouchLeavesReachabilityUnchanged() {
        Harness h = new Harness();
        h.metrics.setLastReachability(SyncMetrics.Reachability.OK);  // previous healthy state
        h.statsReader.readThrows = new java.io.IOException("world/stats/ read failed");

        SyncOutcome outcome = h.runOnce(SyncOrchestrator.Trigger.SCHEDULED);

        assertThat(outcome).isEqualTo(SyncOutcome.FAILURE);
        assertThat(h.metrics.lastReachability()).isEqualTo(SyncMetrics.Reachability.OK);
        assertThat(h.metrics.lastFailureCategory()).isEqualTo(FailureCategory.IO);
    }

    // --------------------------------------------------------------------- harness

    /**
     * Minimal test harness — fakes every collaborator of {@link SyncOrchestrator}
     * so each test case tweaks exactly the behavior it needs.
     */
    private static final class Harness {
        final FakeStatsReader statsReader = new FakeStatsReader();
        final FakeGitService git = new FakeGitService();
        final SyncLock lock = new SyncLock(Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")));
        final SyncMetrics metrics = new SyncMetrics();
        final Clock clock = Clock.fixed(Instant.parse("2026-04-22T12:00:00Z"), ZoneId.of("UTC"));
        final RecordingSleeper sleeper = new RecordingSleeper();
        final Logger logger = Logger.getLogger("SyncOrchestratorTest-" + System.nanoTime());
        final ConfigService configService;
        final List<LogRecord> captured = new ArrayList<>();
        final List<String> capturedMessages = new ArrayList<>();
        final SyncOrchestrator orchestrator;

        Harness() {
            // Wire config: a live TickstatsSyncConfig with sensible defaults.
            TickstatsSyncConfig cfg = new TickstatsSyncConfig(
                    "owner/repo-name",
                    "main",
                    "dummy-token",
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
            // Capture every log message that the orchestrator emits.
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) {
                    captured.add(record);
                    capturedMessages.add(record.getMessage() == null ? "" : record.getMessage());
                }
                @Override public void flush() {}
                @Override public void close() {}
            });
            logger.setLevel(Level.ALL);

            this.orchestrator = new SyncOrchestrator(
                    configService, statsReader, git, lock, metrics, clock, logger, sleeper);

        }

        /** Acquires the lock and runs one cycle — mirrors the caller contract. */
        SyncOutcome runOnce(SyncOrchestrator.Trigger trigger) {
            lock.release();
            boolean acquired = lock.tryAcquire();
            if (!acquired) {
                throw new IllegalStateException("test harness bug: lock already held");
            }
            return orchestrator.runOnce(trigger);
        }

        List<String> capturedMessages() {
            return capturedMessages;
        }
    }

    private static final class FakeStatsReader extends StatsReader {
        java.io.IOException readThrows;
        Map<UUID, byte[]> fileMap = Map.of(U1, "{}".getBytes());

        FakeStatsReader() {
            super(Clock.systemUTC(), Logger.getAnonymousLogger());
        }

        @Override
        public StatsSnapshot read(Path statsDir) throws java.io.IOException {
            if (readThrows != null) throw readThrows;
            return new StatsSnapshot(fileMap, Instant.EPOCH);
        }
    }

    private static final class FakeGitService extends GitService {
        Runnable initOrOpenLocalCloneAction = () -> {};
        Action fetchAndResetAction = () -> {};
        java.util.function.Consumer<Map<UUID, byte[]>> writeFilesAction = data -> {};
        Supplier<Boolean> hasChanges = () -> false;
        Supplier<String> commitAndPush = () -> "0000000";

        FakeGitService() {
            super(dummyConfig(), Path.of("."));
        }

        private static TickstatsSyncConfig dummyConfig() {
            return new TickstatsSyncConfig(
                    "owner/repo", "main", "t", "b", "b@x", "s",
                    Path.of("."), "0 */6 * * *", ZoneId.of("UTC"),
                    false, true, 3, Duration.ofSeconds(10));
        }

        @Override public void initOrOpenLocalClone() throws GitOperationException {
            initOrOpenLocalCloneAction.run();
        }
        @Override public void fetchAndResetToRemote() throws GitOperationException {
            fetchAndResetAction.run();
        }
        @Override public void writeFiles(Map<UUID, byte[]> data) {
            writeFilesAction.accept(data);
        }
        @Override public boolean hasChanges() { return hasChanges.get(); }
        @Override public String commitAndPush(String a, String e, String m) {
            return commitAndPush.get();
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws GitOperationException;
    }

    private static final class RecordingSleeper implements SyncOrchestrator.Sleeper {
        final List<Duration> sleeps = new ArrayList<>();
        @Override public void sleep(Duration duration) {
            sleeps.add(duration);
        }
    }

    /** ConfigService that always returns the same preset config (no YAML involved). */
    private static final class StaticConfigService extends ConfigService {
        private final TickstatsSyncConfig fixed;

        StaticConfigService(TickstatsSyncConfig fixed) {
            super(() -> (FileConfiguration) null, Path.of("."), () -> null);
            this.fixed = fixed;
        }

        @Override public TickstatsSyncConfig current() { return fixed; }
        @Override public TickstatsSyncConfig load() { return fixed; }
    }
}
