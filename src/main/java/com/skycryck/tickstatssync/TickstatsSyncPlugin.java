package com.skycryck.tickstatssync;

import com.skycryck.tickstatssync.command.TickstatsCommand;
import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.git.GitService;
import com.skycryck.tickstatssync.scheduler.CronScheduler;
import com.skycryck.tickstatssync.stats.StatsReader;
import com.skycryck.tickstatssync.sync.SyncLock;
import com.skycryck.tickstatssync.sync.SyncMetrics;
import com.skycryck.tickstatssync.sync.SyncOrchestrator;
import com.skycryck.tickstatssync.util.LogRedactionFilter;
import com.skycryck.tickstatssync.util.PatMasker;
import java.nio.file.Path;
import java.time.Clock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.http.apache.HttpClientConnectionFactory;

public final class TickstatsSyncPlugin extends JavaPlugin {

    private PatMasker patMasker;
    private SyncMetrics metrics;
    private SyncLock syncLock;
    private ConfigService configService;
    private CronScheduler cronScheduler;
    private SyncOrchestrator orchestrator;
    private TickstatsSyncConfig activeConfig;

    @Override
    public void onEnable() {
        // Step 1: PatMasker FIRST — before any logger call.
        this.patMasker = new PatMasker();

        // Step 2: Install LogRedactionFilter immediately — SECOND — before anything logs.
        Logger pluginLogger = getLogger();
        pluginLogger.setFilter(new LogRedactionFilter(patMasker));

        // Step 3: JGit static config.
        HttpTransport.setConnectionFactory(new HttpClientConnectionFactory());
        System.setProperty("org.eclipse.jgit.http.debug", "false");

        // Step 4: Stateless singletons that never see the PAT.
        this.metrics = new SyncMetrics();
        this.syncLock = new SyncLock(Clock.systemUTC());

        // Step 5: ConfigService.load() — may fail; never return early.
        saveDefaultConfig();
        Path serverDirectory = Bukkit.getServer().getWorldContainer().toPath().toAbsolutePath();
        this.configService = new ConfigService(this::getConfig, serverDirectory);
        try {
            this.activeConfig = configService.load();
        } catch (RuntimeException ex) {
            this.activeConfig = null;
            metrics.setConfigInvalidReason(ex.getMessage());
            // Paper already prefixes "[TickstatsSync]" to plugin-logger records; don't double it.
            pluginLogger.warning("config invalid: " + ex.getMessage());
        }

        // Step 6: Seed PatMasker only when config is healthy.
        if (activeConfig != null) {
            patMasker.setToken(activeConfig.token());
        }

        // Step 7: Instantiate remaining services. In inert mode GitService holds a
        // null config and is never invoked; the scheduler is constructed but not started.
        Path workdir = getDataFolder().toPath().resolve("workdir");
        StatsReader statsReader = new StatsReader(Clock.systemUTC(), pluginLogger);
        GitService gitService = new GitService(activeConfig, workdir);
        this.orchestrator = new SyncOrchestrator(
                configService, statsReader, gitService, syncLock, metrics,
                Clock.systemUTC(), pluginLogger);
        this.cronScheduler = new CronScheduler(
                this, configService, orchestrator, metrics, syncLock, pluginLogger);

        // Step 8: Register commands in both healthy and inert mode so /tickstats reload
        // remains operational.
        TickstatsCommand command = new TickstatsCommand(
                configService, syncLock, metrics, orchestrator, cronScheduler, patMasker, this);
        command.register(getLifecycleManager());

        if (activeConfig == null) {
            pluginLogger.info("Plugin loaded in inert mode. Edit plugins/TickstatsSync/config.yml "
                    + "and run /tickstats reload to activate.");
            return;
        }

        // Step 9 (healthy mode only): start the cron scheduler.
        try {
            cronScheduler.start();
            pluginLogger.info("Cron scheduler armed for expression '"
                    + activeConfig.cronExpression() + "' in zone " + activeConfig.timezone());
        } catch (RuntimeException ex) {
            pluginLogger.log(Level.SEVERE,
                    "Failed to arm cron scheduler; entering inert mode", ex);
            metrics.setConfigInvalidReason("scheduler failed to arm: " + ex.getMessage());
            return;
        }

        // Step 10 (healthy + syncOnStartup): fire an immediate sync off the main thread.
        if (activeConfig.syncOnStartup() && syncLock.tryAcquire()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    orchestrator.runOnce(SyncOrchestrator.Trigger.STARTUP);
                } catch (Throwable t) {
                    pluginLogger.log(Level.SEVERE, "Startup sync threw unexpectedly", t);
                }
            });
        }
    }

    @Override
    public void onDisable() {
        if (cronScheduler != null) {
            try {
                cronScheduler.stop();
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
    }
}
