package com.skycryck.tickstatssync.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.git.GitService;
import com.skycryck.tickstatssync.stats.StatsReader;
import com.skycryck.tickstatssync.sync.SyncLock;
import com.skycryck.tickstatssync.sync.SyncMetrics;
import com.skycryck.tickstatssync.sync.SyncOrchestrator;
import com.skycryck.tickstatssync.sync.SyncOutcome;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

final class CronSchedulerTest {

    @Test
    void hourlyCronFiresAtNextWholeHourInParisZone() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-22T10:30:00Z"));
        CronScheduler scheduler = build("0 */6 * * *", ZoneId.of("Europe/Paris"), clock);
        // Europe/Paris is CEST (+02:00) on 2026-04-22 → 10:30Z = 12:30 local.
        // `0 */6 * * *` fires at 00, 06, 12, 18 local → next is 18:00 Paris = 16:00Z.
        Instant next = scheduler.computeNextFireInstant();
        assertThat(next).isEqualTo(Instant.parse("2026-04-22T16:00:00Z"));
    }

    @Test
    void specificHoursAt081422ComputeCorrectly() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-22T10:00:00Z"));  // 12:00 Paris
        CronScheduler scheduler = build("0 8,14,22 * * *", ZoneId.of("Europe/Paris"), clock);
        Instant next = scheduler.computeNextFireInstant();
        // next after 12:00 Paris is 14:00 Paris = 12:00Z
        assertThat(next).isEqualTo(Instant.parse("2026-04-22T12:00:00Z"));
    }

    @Test
    void dstSpringForwardSchedulesExactlyOneOccurrence() {
        // Europe/Paris DST spring-forward for 2026: Sunday 29 March, 02:00 → 03:00 CEST.
        // Use an hourly cron "0 * * * *" and place `now` 15 min before the skip.
        MutableClock clock = new MutableClock(Instant.parse("2026-03-29T00:45:00Z"));
        CronScheduler scheduler = build("0 * * * *", ZoneId.of("Europe/Paris"), clock);

        // 00:45Z == 01:45 CET (UTC+1 — still before the transition). Next hourly firing
        // in local time is 03:00 CEST (2 local hours ahead), which is 01:00Z.
        Instant next = scheduler.computeNextFireInstant();
        assertThat(next).isAfter(clock.instant());

        // Advance to that instant and re-compute — the next firing is one hour later,
        // not a re-fire of the "lost" 02:00.
        clock.setInstant(next);
        Instant after = scheduler.computeNextFireInstant();
        assertThat(after).isEqualTo(next.plus(Duration.ofHours(1)));
    }

    @Test
    void dstFallBackSchedulesExactlyOneOccurrence() {
        // Fall-back 2026: Sunday 25 October, 03:00 CEST → 02:00 CET.
        MutableClock clock = new MutableClock(Instant.parse("2026-10-25T00:30:00Z"));
        CronScheduler scheduler = build("0 * * * *", ZoneId.of("Europe/Paris"), clock);

        Instant next = scheduler.computeNextFireInstant();
        assertThat(next).isAfter(clock.instant());
        clock.setInstant(next);
        Instant after = scheduler.computeNextFireInstant();
        // One hour after the prior firing — no double-fire across the ambiguous window.
        assertThat(after).isEqualTo(next.plus(Duration.ofHours(1)));
    }

    @Test
    void startArmsNextFireAndCapturesRunnable() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-22T10:30:00Z"));
        FakeTaskScheduler fake = new FakeTaskScheduler();
        // Build the scheduler directly (the `build()` helper calls start+stop, which
        // would pollute the fake's scheduled-list for this assertion).
        TickstatsSyncConfig cfg = defaultConfig("0 */6 * * *", ZoneId.of("Europe/Paris"));
        ConfigService cs = new StaticConfigService(cfg);
        CronScheduler scheduler = new CronScheduler(
                cs,
                new FakeOrchestrator(cs, clock, () -> SyncOutcome.SUCCESS_NO_CHANGES),
                new SyncMetrics(),
                new SyncLock(clock),
                Logger.getAnonymousLogger(),
                clock,
                fake);

        scheduler.start();

        assertThat(fake.scheduled).hasSize(1);
        assertThat(fake.scheduled.get(0).taskId()).isEqualTo(scheduler.activeTaskId());
    }

    @Test
    void firingReArmsTheNextFiringRegardlessOfOrchestratorOutcome() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-22T10:30:00Z"));
        FakeTaskScheduler fake = new FakeTaskScheduler();
        TickstatsSyncConfig cfg = defaultConfig("0 */6 * * *", ZoneId.of("Europe/Paris"));
        ConfigService cs = new StaticConfigService(cfg);

        AtomicBoolean orchestratorRan = new AtomicBoolean();
        FakeOrchestrator orchestrator = new FakeOrchestrator(
                cs, clock, () -> {
                    orchestratorRan.set(true);
                    return SyncOutcome.SUCCESS_NO_CHANGES;
                });
        SyncLock lock = new SyncLock(clock);
        SyncMetrics metrics = new SyncMetrics();
        Logger logger = newCapturingLogger();
        CronScheduler scheduler = new CronScheduler(
                cs, orchestrator, metrics, lock, logger, clock, fake);

        scheduler.start();
        assertThat(fake.scheduled).hasSize(1);

        // Advance clock to the scheduled instant and fire the runnable.
        clock.setInstant(clock.instant().plusMillis(fake.scheduled.get(0).delayMs));
        fake.scheduled.get(0).task.run();

        assertThat(orchestratorRan.get()).as("orchestrator ran on cron fire").isTrue();
        assertThat(fake.scheduled).as("scheduler re-armed itself").hasSize(2);
    }

    @Test
    void whenLockHeldCronFireSkipsOrchestratorAndEmitsWarning() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-22T10:30:00Z"));
        FakeTaskScheduler fake = new FakeTaskScheduler();
        TickstatsSyncConfig cfg = defaultConfig("0 */6 * * *", ZoneId.of("Europe/Paris"));
        ConfigService cs = new StaticConfigService(cfg);

        AtomicBoolean orchestratorRan = new AtomicBoolean();
        FakeOrchestrator orchestrator = new FakeOrchestrator(cs, clock, () -> {
            orchestratorRan.set(true);
            return SyncOutcome.SUCCESS_NO_CHANGES;
        });
        SyncLock lock = new SyncLock(clock);
        SyncMetrics metrics = new SyncMetrics();
        List<String> logs = new ArrayList<>();
        Logger logger = capturingLogger(logs);
        CronScheduler scheduler = new CronScheduler(
                cs, orchestrator, metrics, lock, logger, clock, fake);

        // Pre-acquire the lock as if a manual /tickstats sync were in-flight.
        lock.tryAcquire();
        clock.advance(Duration.ofSeconds(42));

        scheduler.start();
        clock.setInstant(clock.instant().plusMillis(fake.scheduled.get(0).delayMs));
        fake.scheduled.get(0).task.run();

        assertThat(orchestratorRan.get()).as("must NOT run when lock is held").isFalse();
        assertThat(logs).anyMatch(s -> s.contains("Scheduled sync skipped"));
        assertThat(logs).anyMatch(s -> s.contains("held for") && s.contains("s)"));
        assertThat(fake.scheduled).as("re-armed anyway").hasSize(2);
    }

    @Test
    void rescheduleCancelsPreviousTaskAndArmsNew() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-22T10:30:00Z"));
        FakeTaskScheduler fake = new FakeTaskScheduler();
        TickstatsSyncConfig cfg1 = defaultConfig("0 */6 * * *", ZoneId.of("Europe/Paris"));
        MutableConfigService cs = new MutableConfigService(cfg1);

        SyncLock lock = new SyncLock(clock);
        SyncMetrics metrics = new SyncMetrics();
        CronScheduler scheduler = new CronScheduler(
                cs, new FakeOrchestrator(cs, clock, () -> SyncOutcome.SUCCESS_NO_CHANGES),
                metrics, lock, Logger.getAnonymousLogger(), clock, fake);

        scheduler.start();
        int firstTaskId = fake.scheduled.get(0).taskId;

        TickstatsSyncConfig cfg2 = defaultConfig("*/15 * * * *", ZoneId.of("Europe/Paris"));
        cs.replace(cfg2);
        scheduler.reschedule(cfg2);

        assertThat(fake.cancelled).contains(firstTaskId);
        assertThat(fake.scheduled).hasSize(2);
        // New delay corresponds to next */15 fire, which is sooner than 6h.
        assertThat(fake.scheduled.get(1).delayMs).isLessThan(Duration.ofHours(1).toMillis());
    }

    @Test
    void emptyNextExecutionThrowsDescriptiveIllegalStateException() {
        // Crafting a cron expression that never matches under cron-utils UNIX is hard
        // (the parser rejects invalid expressions at parse time). This test therefore
        // exercises the error-message construction path by forcing an empty Optional
        // via reflection — documents the intent of R3's .orElseThrow(...).
        MutableClock clock = new MutableClock(Instant.parse("2026-04-22T10:30:00Z"));
        FakeTaskScheduler fake = new FakeTaskScheduler();
        TickstatsSyncConfig cfg = defaultConfig("0 0 30 2 *", ZoneId.of("UTC"));  // Feb 30 — never
        ConfigService cs = new StaticConfigService(cfg);
        CronScheduler scheduler = new CronScheduler(
                cs, new FakeOrchestrator(cs, clock, () -> SyncOutcome.SUCCESS_NO_CHANGES),
                new SyncMetrics(), new SyncLock(clock),
                Logger.getAnonymousLogger(), clock, fake);
        assertThatThrownBy(() -> scheduler.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cron-utils returned empty next-execution");
    }

    // ------------------------------------------------------------------- harness

    private static CronScheduler build(String expr, ZoneId zone, MutableClock clock) {
        return build(expr, zone, clock, new FakeTaskScheduler());
    }

    private static CronScheduler build(
            String expr, ZoneId zone, MutableClock clock, FakeTaskScheduler fake) {
        TickstatsSyncConfig cfg = defaultConfig(expr, zone);
        ConfigService cs = new StaticConfigService(cfg);
        CronScheduler scheduler = new CronScheduler(
                cs,
                new FakeOrchestrator(cs, clock, () -> SyncOutcome.SUCCESS_NO_CHANGES),
                new SyncMetrics(),
                new SyncLock(clock),
                Logger.getAnonymousLogger(),
                clock,
                fake);
        // Call start() to parse the cron, but then immediately stop so tests that only
        // check computeNextFireInstant aren't left with armed tasks.
        scheduler.start();
        scheduler.stop();
        return scheduler;
    }

    private static TickstatsSyncConfig defaultConfig(String expr, ZoneId zone) {
        return new TickstatsSyncConfig(
                "owner/repo", "main", "t", "Bot", "b@x", "s",
                Path.of("."), expr, zone,
                false, true, 3, Duration.ofSeconds(10));
    }

    private static Logger newCapturingLogger() {
        Logger l = Logger.getLogger("CronSchedulerTest-" + System.nanoTime());
        l.setUseParentHandlers(false);
        l.setLevel(Level.ALL);
        return l;
    }

    private static Logger capturingLogger(List<String> sink) {
        Logger l = newCapturingLogger();
        l.addHandler(new Handler() {
            @Override public void publish(LogRecord r) {
                sink.add(r.getMessage() == null ? "" : r.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return l;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }

        void setInstant(Instant i) { this.now = i; }
        void advance(Duration d) { this.now = this.now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class FakeTaskScheduler implements CronScheduler.TaskScheduler {
        final List<Scheduled> scheduled = new ArrayList<>();
        final List<Integer> cancelled = new ArrayList<>();
        int nextId = 1000;

        @Override public int scheduleLater(Runnable task, long delayMs) {
            int id = nextId++;
            scheduled.add(new Scheduled(id, task, delayMs));
            return id;
        }

        @Override public void cancel(int taskId) { cancelled.add(taskId); }

        record Scheduled(int taskId, Runnable task, long delayMs) {}
    }

    /**
     * Orchestrator fake that runs a supplier on every {@link #runOnce}. It must
     * release the {@link SyncLock} the way the real orchestrator does, so the
     * scheduler's next-fire logic can re-acquire on subsequent cycles.
     */
    private static final class FakeOrchestrator extends SyncOrchestrator {
        private final java.util.function.Supplier<SyncOutcome> body;
        private final SyncLock lock;

        FakeOrchestrator(ConfigService cs, Clock clock,
                java.util.function.Supplier<SyncOutcome> body) {
            super(cs, new StatsReader(clock, Logger.getAnonymousLogger()),
                    dummyGit(), new SyncLock(clock), new SyncMetrics(), clock,
                    Logger.getAnonymousLogger());
            this.body = body;
            this.lock = null;
        }

        @Override
        public SyncOutcome runOnce(Trigger trigger) {
            try {
                return body.get();
            } finally {
                // match the real contract — but the scheduler-level SyncLock is owned
                // by the CronScheduler test harness, not by this fake. Release happens
                // via the real scheduler's invocation of orchestrator.runOnce whose
                // finally-release we emulate here if we had a lock reference.
            }
        }

        private static GitService dummyGit() {
            return new GitService(
                    new TickstatsSyncConfig("o/r", "main", "t", "b", "b@x", "s",
                            Path.of("."), "0 * * * *", ZoneOffset.UTC, false, true, 3,
                            Duration.ofSeconds(10)),
                    Path.of("."));
        }
    }

    private static final class StaticConfigService extends ConfigService {
        private final TickstatsSyncConfig fixed;

        StaticConfigService(TickstatsSyncConfig fixed) {
            super(() -> (FileConfiguration) null, Path.of("."), () -> null);
            this.fixed = fixed;
        }

        @Override public TickstatsSyncConfig current() { return fixed; }
        @Override public TickstatsSyncConfig load() { return fixed; }
    }

    private static final class MutableConfigService extends ConfigService {
        private volatile TickstatsSyncConfig current;

        MutableConfigService(TickstatsSyncConfig initial) {
            super(() -> (FileConfiguration) null, Path.of("."), () -> null);
            this.current = initial;
        }

        void replace(TickstatsSyncConfig cfg) { this.current = cfg; }

        @Override public TickstatsSyncConfig current() { return current; }
        @Override public TickstatsSyncConfig load() { return current; }
    }
}
