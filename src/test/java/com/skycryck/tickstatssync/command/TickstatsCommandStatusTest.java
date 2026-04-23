package com.skycryck.tickstatssync.command;

import static org.assertj.core.api.Assertions.assertThat;

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

final class TickstatsCommandStatusTest {

    private static final String TOKEN = "ghp_secret_must_never_appear_in_status_output";

    @Test
    void healthyTemplateFormatsTimestampsInConfigTimezone() {
        Harness h = new Harness();
        h.metrics.setLastSuccessAt(Instant.parse("2026-04-22T12:00:03Z"));  // 14:00:03 Paris
        h.metrics.setNextScheduledAt(Instant.parse("2026-04-22T18:00:00Z"));  // 20:00:00 Paris
        h.metrics.setDetectedStatsFiles(42);
        h.metrics.setLastReachability(SyncMetrics.Reachability.OK);
        h.metrics.setLastOutcome(SyncOutcome.SUCCESS_WITH_COMMIT);
        h.metrics.setLastTrigger(SyncOrchestrator.Trigger.SCHEDULED);
        h.metrics.setLastAttempts(1);

        List<String> lines = new ArrayList<>();
        h.command.handleStatus(lines::add);

        assertThat(lines).containsExactly(
                "\u00a76TickstatsSync \u00a77v1.0.0",
                "\u00a77Last successful sync: \u00a7a2026-04-22 14:00:03 (Europe/Paris)",
                "\u00a77Next scheduled sync:  \u00a7a2026-04-22 20:00:00 (Europe/Paris)",
                "\u00a77Detected stats files: \u00a7a42",
                "\u00a77PAT configured:       \u00a7ayes",
                "\u00a77Repo reachability:    \u00a7aok (checked at last sync)",
                "\u00a77Last outcome:         \u00a7aSUCCESS_WITH_COMMIT (trigger: SCHEDULED, attempts: 1)"
        );
    }

    @Test
    void neverSyncedTemplate() {
        Harness h = new Harness();
        h.metrics.setNextScheduledAt(Instant.parse("2026-04-22T18:00:00Z"));
        h.metrics.setDetectedStatsFiles(42);

        List<String> lines = new ArrayList<>();
        h.command.handleStatus(lines::add);

        assertThat(lines).containsExactly(
                "\u00a76TickstatsSync \u00a77v1.0.0",
                "\u00a77Last successful sync: \u00a7enever",
                "\u00a77Next scheduled sync:  \u00a7a2026-04-22 20:00:00 (Europe/Paris)",
                "\u00a77Detected stats files: \u00a7a42",
                "\u00a77PAT configured:       \u00a7ayes",
                "\u00a77Repo reachability:    \u00a7eunknown",
                "\u00a77Last outcome:         \u00a7enone yet"
        );
    }

    @Test
    void brokenPatShowsRedReachabilityWithCategory() {
        Harness h = new Harness();
        h.metrics.setLastSuccessAt(Instant.parse("2026-04-21T06:00:04Z"));
        h.metrics.setNextScheduledAt(Instant.parse("2026-04-22T18:00:00Z"));
        h.metrics.setDetectedStatsFiles(42);
        h.metrics.setLastReachability(SyncMetrics.Reachability.FAILED);
        h.metrics.setLastFailureCategory(FailureCategory.AUTH);
        h.metrics.setLastOutcome(SyncOutcome.FAILURE);
        h.metrics.setLastTrigger(SyncOrchestrator.Trigger.SCHEDULED);
        h.metrics.setLastAttempts(1);

        List<String> lines = new ArrayList<>();
        h.command.handleStatus(lines::add);

        assertThat(lines).contains(
                "\u00a77Repo reachability:    \u00a7cfailed (AUTH) - check server log",
                "\u00a77Last outcome:         \u00a7cFAILURE (trigger: SCHEDULED, attempts: 1)");
    }

    @Test
    void inertModeShowsConfigInvalidBannerAndOmitsNextScheduled() {
        Harness h = new Harness();
        h.metrics.setConfigInvalidReason("sync.cron is not a valid Unix cron expression");
        h.metrics.setLastSuccessAt(Instant.parse("2026-04-21T12:00:03Z"));
        h.metrics.setDetectedStatsFiles(42);

        List<String> lines = new ArrayList<>();
        h.command.handleStatus(lines::add);

        assertThat(lines).containsExactly(
                "\u00a76TickstatsSync \u00a77v1.0.0",
                "\u00a7c\u26a0 config invalid: sync.cron is not a valid Unix cron expression",
                "\u00a77Edit plugins/TickstatsSync/config.yml and run \u00a7b/tickstats reload\u00a77.",
                "\u00a77Last successful sync: \u00a7a2026-04-21 14:00:03 (Europe/Paris)",
                "\u00a77Detected stats files: \u00a7a42"
        );
        assertThat(lines).as("inert mode omits the Next scheduled sync row")
                .noneMatch(line -> line.contains("Next scheduled sync"));
    }

    @Test
    void patTokenNeverAppearsInAnyLineEvenWhenMaskerHasIt() {
        Harness h = new Harness();
        h.patMasker.setToken(TOKEN);
        h.metrics.setLastSuccessAt(Instant.parse("2026-04-22T12:00:00Z"));
        h.metrics.setNextScheduledAt(Instant.parse("2026-04-22T18:00:00Z"));
        h.metrics.setDetectedStatsFiles(42);
        h.metrics.setLastReachability(SyncMetrics.Reachability.OK);

        List<String> lines = new ArrayList<>();
        h.command.handleStatus(lines::add);

        assertThat(String.join("\n", lines))
                .as("literal token value must never appear in the status output")
                .doesNotContain(TOKEN);
    }

    // ------------------------------------------------------------------- harness

    private static final class Harness {
        final SyncMetrics metrics = new SyncMetrics();
        final PatMasker patMasker = new PatMasker();
        final TickstatsCommand command;

        Harness() {
            TickstatsSyncConfig cfg = new TickstatsSyncConfig(
                    "owner/repo", "main", TOKEN, "Bot", "b@x", "my-server",
                    Path.of("."), "0 */6 * * *", ZoneId.of("Europe/Paris"),
                    false, true, 3, Duration.ofSeconds(10));
            TestFixtures.StaticConfigService configService =
                    new TestFixtures.StaticConfigService(cfg);
            Clock fixedClock = Clock.fixed(Instant.EPOCH, ZoneId.of("UTC"));
            SyncLock lock = new SyncLock(fixedClock);
            SyncOrchestrator orchestrator = new TestFixtures.FakeOrchestrator(
                    configService, fixedClock, () -> SyncOutcome.SUCCESS_NO_CHANGES, lock);
            CronScheduler scheduler = new CronScheduler(
                    configService, orchestrator, metrics, lock,
                    java.util.logging.Logger.getAnonymousLogger(),
                    fixedClock,
                    new TestFixtures.RecordingTaskScheduler());
            this.command = new TickstatsCommand(
                    configService, lock, metrics, orchestrator, scheduler,
                    patMasker, null,
                    () -> "1.0.0",
                    fixedClock,
                    () -> {});
        }
    }
}
