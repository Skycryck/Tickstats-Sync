package com.skycryck.tickstatssync.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.scheduler.CronScheduler;
import com.skycryck.tickstatssync.sync.FailureCategory;
import com.skycryck.tickstatssync.sync.SyncLock;
import com.skycryck.tickstatssync.sync.SyncMetrics;
import com.skycryck.tickstatssync.sync.SyncOrchestrator;
import com.skycryck.tickstatssync.sync.SyncOutcome;
import com.skycryck.tickstatssync.util.PatMasker;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TickstatsCommandSyncTest {

    @Test
    void adminWithLockFreeGetsSyncStartedAndTerminalMessage() {
        Harness h = new Harness();
        h.orchestratorOutcome = SyncOutcome.SUCCESS_WITH_COMMIT;
        // Mirror orchestrator's side-effects on metrics for the terminal message path.
        h.metrics.setLastAttempts(1);
        h.metrics.setDetectedStatsFiles(7);
        h.metrics.setLastCommitSha("abc1234");
        h.metrics.setLastDurationMs(812);

        List<String> immediate = new ArrayList<>();
        List<String> terminal = new ArrayList<>();
        List<Runnable> pendingAsync = new ArrayList<>();

        h.command.handleSync(immediate::add, terminal::add, pendingAsync::add);

        assertThat(immediate).containsExactly("\u00a7a[TickstatsSync] Sync started.");
        assertThat(pendingAsync).hasSize(1);
        pendingAsync.get(0).run();
        assertThat(terminal).containsExactly(
                "\u00a7a[TickstatsSync] Sync complete: pushed 7 file(s), commit abc1234, 812 ms.");
    }

    @Test
    void adminWithLockFreeAndMultipleAttemptsUsesRetriedTemplate() {
        Harness h = new Harness();
        h.orchestratorOutcome = SyncOutcome.SUCCESS_WITH_COMMIT;
        h.metrics.setLastAttempts(3);
        h.metrics.setDetectedStatsFiles(42);
        h.metrics.setLastCommitSha("def5678");
        h.metrics.setLastDurationMs(2348);

        List<String> terminal = new ArrayList<>();
        List<Runnable> pendingAsync = new ArrayList<>();
        h.command.handleSync(m -> {}, terminal::add, pendingAsync::add);
        pendingAsync.get(0).run();

        assertThat(terminal).containsExactly(
                "\u00a7a[TickstatsSync] Sync complete after 3 attempts: pushed 42 file(s), "
                        + "commit def5678, 2348 ms.");
    }

    @Test
    void successNoChangesUsesNoChangesTemplate() {
        Harness h = new Harness();
        h.orchestratorOutcome = SyncOutcome.SUCCESS_NO_CHANGES;
        h.metrics.setLastAttempts(1);

        List<String> terminal = new ArrayList<>();
        List<Runnable> pendingAsync = new ArrayList<>();
        h.command.handleSync(m -> {}, terminal::add, pendingAsync::add);
        pendingAsync.get(0).run();

        assertThat(terminal).containsExactly(
                "\u00a7a[TickstatsSync] Sync complete: no changes since last sync.");
    }

    @Test
    void failureUsesFailureTemplateWithCategoryAndAttempts() {
        Harness h = new Harness();
        h.orchestratorOutcome = SyncOutcome.FAILURE;
        h.metrics.setLastAttempts(3);
        h.metrics.setLastFailureCategory(FailureCategory.NETWORK);

        List<String> terminal = new ArrayList<>();
        List<Runnable> pendingAsync = new ArrayList<>();
        h.command.handleSync(m -> {}, terminal::add, pendingAsync::add);
        pendingAsync.get(0).run();

        assertThat(terminal).containsExactly(
                "\u00a7c[TickstatsSync] Sync failed (NETWORK) after 3 attempts. "
                        + "See server log for details.");
    }

    @Test
    void lockHeldReplyIncludesComputedSecondsAndDoesNotDispatch() {
        Harness h = new Harness();
        // Pre-acquire the lock, then advance the clock by 30s.
        h.lock.tryAcquire();
        h.clock.advance(Duration.ofSeconds(30));

        List<String> immediate = new ArrayList<>();
        List<Runnable> pendingAsync = new ArrayList<>();
        h.command.handleSync(immediate::add, m -> {}, pendingAsync::add);

        assertThat(immediate).containsExactly(
                "\u00a7e[TickstatsSync] A sync is already in progress (started 30s ago). "
                        + "Try again in a moment.");
        assertThat(pendingAsync).isEmpty();
    }

    @Test
    void inertModeRefusesAndPointsAtReload() {
        Harness h = new Harness();
        h.metrics.setConfigInvalidReason("sync.cron is not a valid Unix cron expression");

        List<String> immediate = new ArrayList<>();
        List<Runnable> pendingAsync = new ArrayList<>();
        h.command.handleSync(immediate::add, m -> {}, pendingAsync::add);

        assertThat(immediate).containsExactly(
                "\u00a7c[TickstatsSync] Configuration is invalid "
                        + "(sync.cron is not a valid Unix cron expression). "
                        + "Edit config.yml and run /tickstats reload.");
        assertThat(pendingAsync).isEmpty();
    }

    // ------------------------------------------------------------------- harness

    private static final class Harness {
        final SyncMetrics metrics = new SyncMetrics();
        final TestFixtures.MutableClock clock =
                new TestFixtures.MutableClock(Instant.parse("2026-04-22T12:00:00Z"));
        final SyncLock lock = new SyncLock(clock);
        SyncOutcome orchestratorOutcome = SyncOutcome.SUCCESS_NO_CHANGES;

        final TestFixtures.StaticConfigService configService;
        final TestFixtures.FakeOrchestrator orchestrator;
        final CronScheduler scheduler;
        final TickstatsCommand command;

        Harness() {
            TickstatsSyncConfig cfg = new TickstatsSyncConfig(
                    "owner/repo", "main", "token", "Bot", "b@x", "my-server",
                    Path.of("."), "0 */6 * * *", ZoneId.of("Europe/Paris"),
                    false, true, 3, Duration.ofSeconds(10));
            this.configService = new TestFixtures.StaticConfigService(cfg);
            this.orchestrator = new TestFixtures.FakeOrchestrator(
                    configService, clock, () -> orchestratorOutcome, lock);
            this.scheduler = new CronScheduler(
                    configService, orchestrator, metrics, lock,
                    java.util.logging.Logger.getAnonymousLogger(), clock,
                    new TestFixtures.RecordingTaskScheduler());
            this.command = new TickstatsCommand(
                    configService, lock, metrics, orchestrator, scheduler,
                    new PatMasker(), null,
                    () -> "1.0.0", clock, () -> {});
        }
    }
}
