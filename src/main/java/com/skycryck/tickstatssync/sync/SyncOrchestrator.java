package com.skycryck.tickstatssync.sync;

import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.git.GitService;
import com.skycryck.tickstatssync.stats.StatsReader;
import java.time.Clock;
import java.util.logging.Logger;

public final class SyncOrchestrator {

    private static final String NOT_IMPLEMENTED =
            "TickstatsSync service not yet implemented - Foundational-phase stub, implemented in user story US1";

    public enum Trigger {
        SCHEDULED,
        MANUAL,
        STARTUP
    }

    @SuppressWarnings("unused") private final ConfigService configService;
    @SuppressWarnings("unused") private final StatsReader statsReader;
    @SuppressWarnings("unused") private final GitService gitService;
    @SuppressWarnings("unused") private final SyncLock lock;
    @SuppressWarnings("unused") private final SyncMetrics metrics;
    @SuppressWarnings("unused") private final Clock clock;
    @SuppressWarnings("unused") private final Logger logger;

    public SyncOrchestrator(
            ConfigService configService,
            StatsReader statsReader,
            GitService gitService,
            SyncLock lock,
            SyncMetrics metrics,
            Clock clock,
            Logger logger) {
        this.configService = configService;
        this.statsReader = statsReader;
        this.gitService = gitService;
        this.lock = lock;
        this.metrics = metrics;
        this.clock = clock;
        this.logger = logger;
    }

    public SyncOutcome runOnce(Trigger trigger) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }
}
