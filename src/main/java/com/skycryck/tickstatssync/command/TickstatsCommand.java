package com.skycryck.tickstatssync.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.scheduler.CronScheduler;
import com.skycryck.tickstatssync.sync.SyncLock;
import com.skycryck.tickstatssync.sync.SyncMetrics;
import com.skycryck.tickstatssync.sync.SyncOrchestrator;
import com.skycryck.tickstatssync.util.PatMasker;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.Plugin;

public final class TickstatsCommand {

    private static final String PERMISSION = "tickstats.admin";
    private static final String STUB_RESPONSE = "\u00a7c[TickstatsSync] Subcommand not yet implemented.";

    @SuppressWarnings("unused") private final ConfigService configService;
    @SuppressWarnings("unused") private final SyncLock lock;
    @SuppressWarnings("unused") private final SyncMetrics metrics;
    @SuppressWarnings("unused") private final SyncOrchestrator orchestrator;
    @SuppressWarnings("unused") private final CronScheduler scheduler;
    @SuppressWarnings("unused") private final PatMasker patMasker;
    @SuppressWarnings("unused") private final Plugin plugin;

    public TickstatsCommand(
            ConfigService configService,
            SyncLock lock,
            SyncMetrics metrics,
            SyncOrchestrator orchestrator,
            CronScheduler scheduler,
            PatMasker patMasker,
            Plugin plugin) {
        this.configService = configService;
        this.lock = lock;
        this.metrics = metrics;
        this.orchestrator = orchestrator;
        this.scheduler = scheduler;
        this.patMasker = patMasker;
        this.plugin = plugin;
    }

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

    private int onSync(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendMessage(STUB_RESPONSE);
        return Command.SINGLE_SUCCESS;
    }

    private int onStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendMessage(STUB_RESPONSE);
        return Command.SINGLE_SUCCESS;
    }

    private int onReload(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendMessage(STUB_RESPONSE);
        return Command.SINGLE_SUCCESS;
    }
}
