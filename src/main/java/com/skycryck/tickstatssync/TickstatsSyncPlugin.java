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
import java.util.logging.Logger;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.http.apache.HttpClientConnectionFactory;
import org.bukkit.plugin.java.JavaPlugin;

public final class TickstatsSyncPlugin extends JavaPlugin {

    private PatMasker patMasker;
    private SyncMetrics metrics;
    private SyncLock syncLock;
    private ConfigService configService;
    private CronScheduler cronScheduler;
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
        this.configService = new ConfigService(this::getConfig, getDataFolder().toPath().getParent().getParent());
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

        // Step 7: Instantiate remaining services via the Foundational stubs. They never
        // run their method bodies before US1 lands; holding references is a no-op.
        Path workdir = getDataFolder().toPath().resolve("workdir");
        StatsReader statsReader = new StatsReader();
        GitService gitService = new GitService(activeConfig, workdir);
        SyncOrchestrator orchestrator = new SyncOrchestrator(
                configService, statsReader, gitService, syncLock, metrics, Clock.systemUTC(), pluginLogger);
        this.cronScheduler = new CronScheduler(
                this, configService, orchestrator, metrics, syncLock, pluginLogger);

        // Step 8: Register commands in both healthy and inert mode so /tickstats reload
        // remains operational.
        TickstatsCommand command = new TickstatsCommand(
                configService, syncLock, metrics, orchestrator, cronScheduler, patMasker, this);
        command.register(getLifecycleManager());

        // Step 9 (healthy mode only): start the cron scheduler.
        //   TODO(US1 T035): once CronScheduler.start is implemented, call cronScheduler.start() here.
        // Step 10 (healthy + syncOnStartup): fire an immediate sync.
        //   TODO(US1 T035): once SyncOrchestrator.runOnce is implemented, submit
        //     Bukkit.getScheduler().runTaskAsynchronously(this, () -> orchestrator.runOnce(Trigger.STARTUP))
        //     when activeConfig != null && activeConfig.syncOnStartup() && syncLock.tryAcquire().
    }

    @Override
    public void onDisable() {
        // TODO(US1 T035): cronScheduler.stop() once the scheduler is live.
    }
}
