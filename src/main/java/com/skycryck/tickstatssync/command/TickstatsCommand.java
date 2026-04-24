package com.skycryck.tickstatssync.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.scheduler.CronScheduler;
import com.skycryck.tickstatssync.sync.FailureCategory;
import com.skycryck.tickstatssync.sync.SyncLock;
import com.skycryck.tickstatssync.sync.SyncMetrics;
import com.skycryck.tickstatssync.sync.SyncOrchestrator;
import com.skycryck.tickstatssync.sync.SyncOutcome;
import com.skycryck.tickstatssync.util.PatMasker;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class TickstatsCommand {

    private static final String PERMISSION = "tickstats.admin";
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConfigService configService;
    private final SyncLock lock;
    private final SyncMetrics metrics;
    private final SyncOrchestrator orchestrator;
    private final CronScheduler scheduler;
    private final PatMasker patMasker;
    private final Plugin plugin;
    private final Supplier<String> versionSupplier;
    private final Clock clock;
    private final Runnable reloadDiskConfig;

    /** Production constructor — binds to Paper's clock + {@code plugin.reloadConfig()}. */
    public TickstatsCommand(
            ConfigService configService,
            SyncLock lock,
            SyncMetrics metrics,
            SyncOrchestrator orchestrator,
            CronScheduler scheduler,
            PatMasker patMasker,
            Plugin plugin) {
        this(configService, lock, metrics, orchestrator, scheduler, patMasker, plugin,
                () -> plugin.getPluginMeta().getVersion(),
                Clock.systemUTC(),
                plugin::reloadConfig);
    }

    /** Test constructor — injectable version string, clock, and disk-reload trigger. */
    TickstatsCommand(
            ConfigService configService,
            SyncLock lock,
            SyncMetrics metrics,
            SyncOrchestrator orchestrator,
            CronScheduler scheduler,
            PatMasker patMasker,
            Plugin plugin,
            Supplier<String> versionSupplier,
            Clock clock,
            Runnable reloadDiskConfig) {
        this.configService = configService;
        this.lock = lock;
        this.metrics = metrics;
        this.orchestrator = orchestrator;
        this.scheduler = scheduler;
        this.patMasker = patMasker;
        this.plugin = plugin;
        this.versionSupplier = versionSupplier;
        this.clock = clock;
        this.reloadDiskConfig = reloadDiskConfig;
    }

    // ------------------------------------------------------------------ registration

    public void register(LifecycleEventManager<Plugin> lifecycleManager) {
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralCommandNode<CommandSourceStack> node = Commands.literal("tickstats")
                    .requires(src -> src.getSender().hasPermission(PERMISSION))
                    .then(Commands.literal("sync")
                            .requires(src -> src.getSender().hasPermission(PERMISSION))
                            .executes(this::onSync))
                    .then(Commands.literal("status")
                            .requires(src -> src.getSender().hasPermission(PERMISSION))
                            .executes(this::onStatus))
                    .then(Commands.literal("reload")
                            .requires(src -> src.getSender().hasPermission(PERMISSION))
                            .executes(this::onReload))
                    .build();
            event.registrar().register(node, "TickstatsSync administration");
        });
    }

    // ---------------------------------------------------------- Brigadier adapters

    private int onSync(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        handleSync(
                msg -> sender.sendMessage(msg),
                msg -> deliverIfStillOnline(sender, msg),
                task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
        return Command.SINGLE_SUCCESS;
    }

    private int onStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        handleStatus(sender::sendMessage);
        return Command.SINGLE_SUCCESS;
    }

    private int onReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        handleReload(sender::sendMessage);
        return Command.SINGLE_SUCCESS;
    }

    private static void deliverIfStillOnline(CommandSender sender, String msg) {
        // Per contracts/commands.md §/tickstats sync: if the invoker logged out
        // before the async cycle finished, drop the terminal message silently.
        if (sender instanceof Player p && !p.isOnline()) {
            return;
        }
        sender.sendMessage(msg);
    }

    // -------------------------------------------------------- US3 — /tickstats sync

    /**
     * Package-private entry point used by the Brigadier adapter and directly by unit
     * tests. Splitting the collaborators into plain lambdas keeps the method
     * hermetic (no Paper API in the test path).
     *
     * @param immediateReply called synchronously with the "Sync started" or rejection message.
     * @param terminalReply called when {@code orchestrator.runOnce} returns, with the
     *     post-sync template. May be a no-op if the sender is no longer online.
     * @param asyncRunner schedules the cycle off-thread. Production: Paper's async scheduler.
     */
    void handleSync(
            Consumer<String> immediateReply,
            Consumer<String> terminalReply,
            Consumer<Runnable> asyncRunner) {
        // Inert-mode guard — refuse politely and point at /tickstats reload.
        String reason = metrics.configInvalidReason();
        if (reason != null) {
            immediateReply.accept("\u00a7c[TickstatsSync] Configuration is invalid ("
                    + reason + "). Edit config.yml and run /tickstats reload.");
            return;
        }

        if (!lock.tryAcquire()) {
            long heldForSeconds = lock.heldSince()
                    .map(since -> Math.max(0L,
                            Duration.between(since, clock.instant()).getSeconds()))
                    .orElse(0L);
            immediateReply.accept("\u00a7e[TickstatsSync] A sync is already in progress (started "
                    + heldForSeconds + "s ago). Try again in a moment.");
            return;
        }

        immediateReply.accept("\u00a7a[TickstatsSync] Sync started.");
        asyncRunner.accept(() -> {
            SyncOutcome outcome;
            try {
                outcome = orchestrator.runOnce(SyncOrchestrator.Trigger.MANUAL);
            } catch (Throwable t) {
                outcome = SyncOutcome.FAILURE;
            }
            terminalReply.accept(formatTerminalMessage(outcome));
        });
    }

    private String formatTerminalMessage(SyncOutcome outcome) {
        int attempts = metrics.lastAttempts();
        int files = metrics.detectedStatsFiles();
        String sha = metrics.lastCommitSha();
        long durationMs = metrics.lastDurationMs();
        FailureCategory cat = metrics.lastFailureCategory();

        return switch (outcome) {
            case SUCCESS_WITH_COMMIT -> (attempts > 1)
                    ? "\u00a7a[TickstatsSync] Sync complete after " + attempts + " attempts: pushed "
                            + files + " file(s), commit " + safeSha(sha) + ", " + durationMs + " ms."
                    : "\u00a7a[TickstatsSync] Sync complete: pushed " + files + " file(s), commit "
                            + safeSha(sha) + ", " + durationMs + " ms.";
            case SUCCESS_NO_CHANGES ->
                    "\u00a7a[TickstatsSync] Sync complete: no changes since last sync.";
            case FAILURE -> "\u00a7c[TickstatsSync] Sync failed ("
                    + (cat == null ? "UNKNOWN" : cat) + ") after " + attempts
                    + " attempts. See server log for details.";
        };
    }

    private static String safeSha(String sha) {
        return sha == null ? "?" : sha;
    }

    // ---------------------------------------------------- US4 — /tickstats status

    /** Package-private entry point for testing. */
    void handleStatus(Consumer<String> reply) {
        String version = versionSupplier.get();
        String reason = metrics.configInvalidReason();
        boolean inert = reason != null;

        // Snapshot every field once to avoid flicker during rendering (contracts/commands.md).
        Instant lastSuccessAt = metrics.lastSuccessAt();
        Instant nextScheduledAt = metrics.nextScheduledAt();
        int detectedFiles = metrics.detectedStatsFiles();
        SyncMetrics.Reachability reach = metrics.lastReachability();
        SyncOutcome lastOutcome = metrics.lastOutcome();
        SyncOrchestrator.Trigger lastTrigger = metrics.lastTrigger();
        int lastAttempts = metrics.lastAttempts();
        FailureCategory failureCategory = metrics.lastFailureCategory();

        TickstatsSyncConfig cfg = configService.current();
        ZoneId zone = cfg != null ? cfg.timezone() : ZoneId.systemDefault();

        reply.accept("\u00a76TickstatsSync \u00a77v" + version);

        if (inert) {
            reply.accept("\u00a7c\u26a0 config invalid: " + reason);
            reply.accept("\u00a77Edit plugins/TickstatsSync/config.yml and run "
                    + "\u00a7b/tickstats reload\u00a77.");
            reply.accept("\u00a77Last successful sync: " + formatInstantOrNever(lastSuccessAt, zone));
            reply.accept("\u00a77Detected stats files: \u00a7a" + detectedFiles);
            return;
        }

        boolean patConfigured = cfg != null && cfg.token() != null && !cfg.token().isEmpty();
        reply.accept("\u00a77Last successful sync: " + formatInstantOrNever(lastSuccessAt, zone));
        reply.accept("\u00a77Next scheduled sync:  "
                + formatInstantOrUnscheduled(nextScheduledAt, zone));
        reply.accept("\u00a77Detected stats files: \u00a7a" + detectedFiles);
        reply.accept("\u00a77PAT configured:       " + (patConfigured ? "\u00a7ayes" : "\u00a7cno"));
        reply.accept("\u00a77Repo reachability:    " + formatReachability(reach, failureCategory));
        reply.accept("\u00a77Last outcome:         "
                + formatLastOutcome(lastOutcome, lastTrigger, lastAttempts));
    }

    private static String formatInstantOrNever(Instant i, ZoneId zone) {
        if (i == null) {
            return "\u00a7enever";
        }
        return "\u00a7a"
                + ZonedDateTime.ofInstant(i, zone).format(TIMESTAMP_FMT)
                + " (" + zone.getId() + ")";
    }

    private static String formatInstantOrUnscheduled(Instant i, ZoneId zone) {
        if (i == null) {
            return "\u00a7eunscheduled";
        }
        return "\u00a7a"
                + ZonedDateTime.ofInstant(i, zone).format(TIMESTAMP_FMT)
                + " (" + zone.getId() + ")";
    }

    private static String formatReachability(
            SyncMetrics.Reachability r, FailureCategory fc) {
        return switch (r) {
            case OK -> "\u00a7aok (checked at last sync)";
            case FAILED -> "\u00a7cfailed"
                    + (fc != null ? " (" + fc + ")" : "")
                    + " - check server log";
            case UNKNOWN -> "\u00a7eunknown";
        };
    }

    private static String formatLastOutcome(
            SyncOutcome outcome, SyncOrchestrator.Trigger trigger, int attempts) {
        if (outcome == null) {
            return "\u00a7enone yet";
        }
        String color = outcome == SyncOutcome.FAILURE ? "\u00a7c" : "\u00a7a";
        return color + outcome
                + " (trigger: " + (trigger == null ? "?" : trigger)
                + ", attempts: " + attempts + ")";
    }

    // --------------------------------------------------- US5 — /tickstats reload

    /** Package-private entry point for testing. */
    void handleReload(Consumer<String> reply) {
        reloadDiskConfig.run();

        TickstatsSyncConfig newConfig;
        try {
            newConfig = configService.load();
        } catch (RuntimeException ex) {
            reply.accept("\u00a7c[TickstatsSync] Reload failed: " + ex.getMessage()
                    + ". Previous configuration still active.");
            return;
        }

        boolean wasInert = metrics.configInvalidReason() != null;

        // R13 ordering — critical for credential safety:
        //   1. Update PatMasker FIRST so any log line emitted between steps 1 and 2
        //      that mentions the new token is already redacted.
        patMasker.setToken(newConfig.token());
        //   2. Swap the active config atomically.
        configService.swap(newConfig);
        metrics.setConfigInvalidReason(null);
        //   3. Rearm or start the scheduler against the new expression.
        if (wasInert) {
            scheduler.start();
            reply.accept("\u00a7a[TickstatsSync] Configuration reloaded - plugin is now active. "
                    + "Next sync: " + formatNextFire(newConfig.timezone()) + ".");
        } else {
            scheduler.reschedule(newConfig);
            reply.accept("\u00a7a[TickstatsSync] Configuration reloaded. Next sync: "
                    + formatNextFire(newConfig.timezone()) + ".");
        }
    }

    private String formatNextFire(ZoneId zone) {
        Instant next = metrics.nextScheduledAt();
        if (next == null) {
            return "unscheduled";
        }
        return ZonedDateTime.ofInstant(next, zone).format(TIMESTAMP_FMT)
                + " (" + zone.getId() + ")";
    }
}
