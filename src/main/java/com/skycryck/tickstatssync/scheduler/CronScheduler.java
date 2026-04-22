package com.skycryck.tickstatssync.scheduler;

import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.sync.SyncLock;
import com.skycryck.tickstatssync.sync.SyncMetrics;
import com.skycryck.tickstatssync.sync.SyncOrchestrator;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

public final class CronScheduler {

    private static final String NOT_IMPLEMENTED =
            "TickstatsSync service not yet implemented - Foundational-phase stub, implemented in user story US1";

    @SuppressWarnings("unused") private final Plugin plugin;
    @SuppressWarnings("unused") private final ConfigService configService;
    @SuppressWarnings("unused") private final SyncOrchestrator orchestrator;
    @SuppressWarnings("unused") private final SyncMetrics metrics;
    @SuppressWarnings("unused") private final SyncLock lock;
    @SuppressWarnings("unused") private final Logger logger;

    public CronScheduler(
            Plugin plugin,
            ConfigService configService,
            SyncOrchestrator orchestrator,
            SyncMetrics metrics,
            SyncLock lock,
            Logger logger) {
        this.plugin = plugin;
        this.configService = configService;
        this.orchestrator = orchestrator;
        this.metrics = metrics;
        this.lock = lock;
        this.logger = logger;
    }

    public void start() {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    public void stop() {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    public void reschedule(TickstatsSyncConfig newConfig) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }
}
