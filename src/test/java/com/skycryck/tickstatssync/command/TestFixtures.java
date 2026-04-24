package com.skycryck.tickstatssync.command;

import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.git.GitService;
import com.skycryck.tickstatssync.scheduler.CronScheduler;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Shared fixtures for the {@code command} test package. Keeping the
 * infrastructure here avoids a dozen duplicated inner classes across the
 * three TickstatsCommand* test files.
 */
final class TestFixtures {

    private TestFixtures() {}

    /** ConfigService whose {@link #current()} is backed directly by a mutable field. */
    static class StaticConfigService extends ConfigService {
        private volatile TickstatsSyncConfig active;
        private volatile TickstatsSyncConfig nextLoaded;
        private volatile RuntimeException nextLoadException;

        StaticConfigService(TickstatsSyncConfig initial) {
            super(() -> (FileConfiguration) null, Path.of("."), () -> null);
            this.active = initial;
            this.nextLoaded = initial;
        }

        /** Queue a specific record to return from the next {@link #load()} call. */
        void queueNextLoad(TickstatsSyncConfig next) {
            this.nextLoaded = next;
            this.nextLoadException = null;
        }

        /** Force the next {@link #load()} call to throw the given validation error. */
        void queueNextLoadFailure(RuntimeException ex) {
            this.nextLoadException = ex;
        }

        @Override
        public TickstatsSyncConfig current() {
            return active;
        }

        @Override
        public TickstatsSyncConfig load() {
            if (nextLoadException != null) {
                RuntimeException e = nextLoadException;
                nextLoadException = null;
                throw e;
            }
            return nextLoaded;
        }

        @Override
        public void swap(TickstatsSyncConfig next) {
            this.active = next;
        }
    }

    /**
     * Orchestrator fake that runs a {@link Supplier} on every {@link #runOnce} call
     * and always releases the shared {@link SyncLock} in its finally block — mirroring
     * the real orchestrator's Release discipline so the /tickstats sync "already in
     * progress" rejection test path works.
     */
    static final class FakeOrchestrator extends SyncOrchestrator {
        private final Supplier<SyncOutcome> body;
        private final SyncLock sharedLock;

        FakeOrchestrator(ConfigService cs, Clock clock,
                Supplier<SyncOutcome> body, SyncLock sharedLock) {
            super(cs,
                    new StatsReader(clock, Logger.getAnonymousLogger()),
                    new GitService(cs.current(), Path.of(".")),
                    sharedLock,
                    new SyncMetrics(),
                    clock,
                    Logger.getAnonymousLogger());
            this.body = body;
            this.sharedLock = sharedLock;
        }

        @Override
        public SyncOutcome runOnce(Trigger trigger) {
            try {
                return body.get();
            } finally {
                sharedLock.release();
            }
        }
    }

    /** Monotonically-advancing test clock with mutable instant. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = now.plus(d); }
        void setInstant(Instant i) { this.now = i; }

        @Override public ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Minimal TaskScheduler that captures every scheduleLater + cancel call. */
    static final class RecordingTaskScheduler implements CronScheduler.TaskScheduler {
        final List<Scheduled> scheduled = new ArrayList<>();
        final List<Integer> cancelled = new ArrayList<>();
        int nextId = 1000;

        @Override
        public int scheduleLater(Runnable task, long delayMs) {
            int id = nextId++;
            scheduled.add(new Scheduled(id, task, delayMs));
            return id;
        }

        @Override
        public void cancel(int taskId) {
            cancelled.add(taskId);
        }

        record Scheduled(int taskId, Runnable task, long delayMs) {}
    }
}
