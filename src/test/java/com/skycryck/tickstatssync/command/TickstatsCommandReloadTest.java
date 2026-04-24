package com.skycryck.tickstatssync.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.scheduler.CronScheduler;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TickstatsCommandReloadTest {

    private static final String OLD_TOKEN = "ghp_OLD_TOKEN_0000000000000000000000000000";
    private static final String NEW_TOKEN = "ghp_NEW_TOKEN_1111111111111111111111111111";

    @Test
    void validReloadCallsSetTokenBeforeSwapThenReschedulesScheduler() {
        Harness h = new Harness();
        // Record the order in which the masker is updated and the config is swapped.
        List<String> orderLog = new ArrayList<>();
        h.recordingMasker.onSetTokenCallback =
                token -> orderLog.add("setToken:" + tail(token));
        h.configService.onSwapCallback =
                cfg -> orderLog.add("swap:" + tail(cfg.token()));

        TickstatsSyncConfig newCfg = newConfigWithToken(NEW_TOKEN);
        h.configService.queueNextLoad(newCfg);

        List<String> reply = new ArrayList<>();
        h.command.handleReload(reply::add);

        assertThat(orderLog)
                .as("PatMasker.setToken MUST run before ConfigService.swap (R13)")
                .containsExactly("setToken:" + tail(NEW_TOKEN), "swap:" + tail(NEW_TOKEN));
        assertThat(h.scheduler.rescheduleCalls).hasSize(1);
        assertThat(h.scheduler.rescheduleCalls.get(0).cronExpression())
                .isEqualTo(newCfg.cronExpression());
        assertThat(h.scheduler.startCalls).isZero();
        assertThat(reply).hasSize(1);
        assertThat(reply.get(0)).startsWith(
                "\u00a7a[TickstatsSync] Configuration reloaded. Next sync: ");
    }

    @Test
    void invalidReloadKeepsPreviousConfigActiveAndDoesNotRescheduleOrSetToken() {
        Harness h = new Harness();
        TickstatsSyncConfig previous = h.configService.current();
        h.recordingMasker.setToken(OLD_TOKEN);
        h.configService.queueNextLoadFailure(new RuntimeException(
                "config error: sync.cron is not a valid Unix cron expression"));

        List<String> reply = new ArrayList<>();
        h.command.handleReload(reply::add);

        assertThat(h.configService.current())
                .as("previous config stays active on validation failure")
                .isSameAs(previous);
        assertThat(h.scheduler.rescheduleCalls).isEmpty();
        assertThat(h.scheduler.startCalls).isZero();
        assertThat(h.recordingMasker.currentToken).isEqualTo(OLD_TOKEN);
        assertThat(reply).containsExactly(
                "\u00a7c[TickstatsSync] Reload failed: config error: "
                        + "sync.cron is not a valid Unix cron expression. "
                        + "Previous configuration still active.");
    }

    @Test
    void recoveringFromInertModeCallsStartNotReschedule() {
        Harness h = new Harness();
        h.metrics.setConfigInvalidReason("config error: github.repo is missing");

        TickstatsSyncConfig newCfg = newConfigWithToken(NEW_TOKEN);
        h.configService.queueNextLoad(newCfg);

        List<String> reply = new ArrayList<>();
        h.command.handleReload(reply::add);

        assertThat(h.scheduler.startCalls).isEqualTo(1);
        assertThat(h.scheduler.rescheduleCalls).isEmpty();
        assertThat(h.metrics.configInvalidReason())
                .as("reload clears the inert flag").isNull();
        assertThat(reply).hasSize(1);
        assertThat(reply.get(0)).startsWith(
                "\u00a7a[TickstatsSync] Configuration reloaded - plugin is now active. Next sync: ");
    }

    private static TickstatsSyncConfig newConfigWithToken(String token) {
        return new TickstatsSyncConfig(
                "owner/repo", "main", token, "Bot", "b@x", "my-server",
                Path.of("."), "0 */6 * * *", ZoneId.of("Europe/Paris"),
                false, true, 3, Duration.ofSeconds(10));
    }

    private static String tail(String token) {
        return token == null ? "null" : token.substring(Math.max(0, token.length() - 6));
    }

    // ------------------------------------------------------------------ harness

    private static final class Harness {
        final SyncMetrics metrics = new SyncMetrics();
        final RecordingPatMasker recordingMasker = new RecordingPatMasker();
        final RecordingScheduler scheduler;
        final SpyConfigService configService;
        final SyncLock lock = new SyncLock(Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")));
        final TickstatsCommand command;

        Harness() {
            TickstatsSyncConfig initial = newConfigWithToken(OLD_TOKEN);
            this.configService = new SpyConfigService(initial);
            SyncOrchestrator orchestrator = new TestFixtures.FakeOrchestrator(
                    configService, Clock.systemUTC(),
                    () -> SyncOutcome.SUCCESS_NO_CHANGES, lock);
            this.scheduler = new RecordingScheduler(
                    configService, orchestrator, metrics, lock);
            this.command = new TickstatsCommand(
                    configService, lock, metrics, orchestrator, scheduler,
                    recordingMasker, null,
                    () -> "1.0.0",
                    Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")),
                    () -> {});
        }
    }

    /** PatMasker that records every setToken call so the test can assert ordering. */
    private static final class RecordingPatMasker extends PatMasker {
        volatile String currentToken;
        java.util.function.Consumer<String> onSetTokenCallback = t -> {};

        @Override
        public void setToken(String token) {
            super.setToken(token);
            this.currentToken = token;
            onSetTokenCallback.accept(token);
        }
    }

    /** ConfigService spy — allows queueing load results + observing swap order. */
    private static final class SpyConfigService extends TestFixtures.StaticConfigService {
        java.util.function.Consumer<TickstatsSyncConfig> onSwapCallback = cfg -> {};

        SpyConfigService(TickstatsSyncConfig initial) {
            super(initial);
        }

        @Override
        public void swap(TickstatsSyncConfig next) {
            super.swap(next);
            onSwapCallback.accept(next);
        }
    }

    /**
     * Scheduler spy — every reschedule and start call is counted so the test can
     * distinguish inert-recovery (start) from normal reload (reschedule).
     */
    private static final class RecordingScheduler extends CronScheduler {
        final List<TickstatsSyncConfig> rescheduleCalls = new ArrayList<>();
        int startCalls = 0;
        int stopCalls = 0;

        RecordingScheduler(
                ConfigService cs, SyncOrchestrator orchestrator,
                SyncMetrics metrics, SyncLock lock) {
            super(cs, orchestrator, metrics, lock,
                    java.util.logging.Logger.getAnonymousLogger(),
                    Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")),
                    new TestFixtures.RecordingTaskScheduler());
        }

        @Override public void start() { startCalls++; }
        @Override public void stop() { stopCalls++; }
        @Override public void reschedule(TickstatsSyncConfig newConfig) {
            rescheduleCalls.add(newConfig);
        }
    }

    @SuppressWarnings("unused")
    private static final AtomicReference<Object> KEEP_IMPORT = new AtomicReference<>();
}
