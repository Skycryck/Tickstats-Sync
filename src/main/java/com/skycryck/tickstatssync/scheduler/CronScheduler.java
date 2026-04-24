package com.skycryck.tickstatssync.scheduler;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.sync.SyncLock;
import com.skycryck.tickstatssync.sync.SyncMetrics;
import com.skycryck.tickstatssync.sync.SyncOrchestrator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class CronScheduler {

    /**
     * Minimal scheduler abstraction — production plugs in Paper's
     * {@code BukkitScheduler.runTaskLaterAsynchronously}; tests substitute a fake.
     */
    public interface TaskScheduler {
        int scheduleLater(Runnable task, long delayMs);

        void cancel(int taskId);
    }

    public static TaskScheduler bukkit(Plugin plugin) {
        return new TaskScheduler() {
            @Override
            public int scheduleLater(Runnable task, long delayMs) {
                long ticks = Math.max(1L, delayMs / 50L);
                return Bukkit.getScheduler()
                        .runTaskLaterAsynchronously(plugin, task, ticks)
                        .getTaskId();
            }

            @Override
            public void cancel(int taskId) {
                Bukkit.getScheduler().cancelTask(taskId);
            }
        };
    }

    private final ConfigService configService;
    private final SyncOrchestrator orchestrator;
    private final SyncMetrics metrics;
    private final SyncLock lock;
    private final Logger logger;
    private final Clock clock;
    private final TaskScheduler taskScheduler;

    private volatile ExecutionTime executionTime;
    private volatile ZoneId zone;
    private volatile int activeTaskId = -1;
    private volatile String cronExpression;

    /** Production constructor — binds to Paper's scheduler and the system UTC clock. */
    public CronScheduler(
            Plugin plugin,
            ConfigService configService,
            SyncOrchestrator orchestrator,
            SyncMetrics metrics,
            SyncLock lock,
            Logger logger) {
        this(configService, orchestrator, metrics, lock, logger,
                Clock.systemUTC(), bukkit(plugin));
    }

    /** Test constructor — inject a virtual clock and a fake task scheduler. */
    public CronScheduler(
            ConfigService configService,
            SyncOrchestrator orchestrator,
            SyncMetrics metrics,
            SyncLock lock,
            Logger logger,
            Clock clock,
            TaskScheduler taskScheduler) {
        this.configService = configService;
        this.orchestrator = orchestrator;
        this.metrics = metrics;
        this.lock = lock;
        this.logger = logger;
        this.clock = clock;
        this.taskScheduler = taskScheduler;
    }

    public void start() {
        reparse(configService.current());
        scheduleNext();
    }

    public void stop() {
        int tid = activeTaskId;
        if (tid >= 0) {
            taskScheduler.cancel(tid);
            activeTaskId = -1;
        }
    }

    public void reschedule(TickstatsSyncConfig newConfig) {
        stop();
        reparse(newConfig);
        scheduleNext();
    }

    private void reparse(TickstatsSyncConfig cfg) {
        CronParser parser = new CronParser(
                CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
        this.executionTime = ExecutionTime.forCron(parser.parse(cfg.cronExpression()));
        this.zone = cfg.timezone();
        this.cronExpression = cfg.cronExpression();
    }

    private void scheduleNext() {
        Instant nextInstant = computeNextFireInstant();
        long deltaMs = Math.max(1L, Duration.between(clock.instant(), nextInstant).toMillis());
        metrics.setNextScheduledAt(nextInstant);
        activeTaskId = taskScheduler.scheduleLater(this::fire, deltaMs);
    }

    /** Package-private for tests: computes the next firing instant from {@code clock.instant()}. */
    Instant computeNextFireInstant() {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), zone);
        ZonedDateTime next = executionTime.nextExecution(now)
                .orElseThrow(() -> new IllegalStateException(
                        "cron-utils returned empty next-execution for expression '"
                                + cronExpression + "' in zone " + zone));
        return next.toInstant();
    }

    private void fire() {
        try {
            if (lock.tryAcquire()) {
                try {
                    orchestrator.runOnce(SyncOrchestrator.Trigger.SCHEDULED);
                } catch (Throwable t) {
                    // Orchestrator is expected to catch its own exceptions; anything
                    // that escapes is a last-ditch net so the scheduler loop survives.
                    logger.log(Level.SEVERE, "uncaught exception during scheduled sync", t);
                }
            } else {
                Instant heldSince = lock.heldSince().orElse(clock.instant());
                long heldForSeconds = Math.max(0L,
                        Duration.between(heldSince, clock.instant()).getSeconds());
                logger.warning("Scheduled sync skipped: previous sync still running (held for "
                        + heldForSeconds + "s)");
            }
        } finally {
            // R6 invariant: arm the next firing regardless of outcome so the
            // scheduler loop never dies silently.
            scheduleNext();
        }
    }

    // --- Test helpers (package-private) --------------------------------------

    int activeTaskId() {
        return activeTaskId;
    }
}
