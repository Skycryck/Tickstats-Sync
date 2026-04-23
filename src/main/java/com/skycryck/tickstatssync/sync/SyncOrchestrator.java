package com.skycryck.tickstatssync.sync;

import com.skycryck.tickstatssync.config.ConfigService;
import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import com.skycryck.tickstatssync.git.GitOperationException;
import com.skycryck.tickstatssync.git.GitService;
import com.skycryck.tickstatssync.stats.StatsReader;
import com.skycryck.tickstatssync.stats.StatsSnapshot;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SyncOrchestrator {

    public enum Trigger {
        SCHEDULED,
        MANUAL,
        STARTUP
    }

    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;

        Sleeper REAL = duration -> Thread.sleep(Math.max(0L, duration.toMillis()));
    }

    static final String ABANDON_LOG_MESSAGE =
            "giving up immediately - non-transient failure, check your PAT and repo configuration";

    private static final DateTimeFormatter COMMIT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ConfigService configService;
    private final StatsReader statsReader;
    private final GitService gitService;
    private final SyncLock lock;
    private final SyncMetrics metrics;
    private final Clock clock;
    private final Logger logger;
    private final Sleeper sleeper;

    public SyncOrchestrator(
            ConfigService configService,
            StatsReader statsReader,
            GitService gitService,
            SyncLock lock,
            SyncMetrics metrics,
            Clock clock,
            Logger logger) {
        this(configService, statsReader, gitService, lock, metrics, clock, logger, Sleeper.REAL);
    }

    public SyncOrchestrator(
            ConfigService configService,
            StatsReader statsReader,
            GitService gitService,
            SyncLock lock,
            SyncMetrics metrics,
            Clock clock,
            Logger logger,
            Sleeper sleeper) {
        this.configService = configService;
        this.statsReader = statsReader;
        this.gitService = gitService;
        this.lock = lock;
        this.metrics = metrics;
        this.clock = clock;
        this.logger = logger;
        this.sleeper = sleeper;
    }

    /**
     * Runs one full sync cycle. The caller is expected to have acquired {@link SyncLock}
     * before invoking; {@code runOnce} unconditionally releases it in its finally block
     * (data-model § SyncLock Release discipline).
     */
    public SyncOutcome runOnce(Trigger trigger) {
        Instant startedAt = clock.instant();
        TickstatsSyncConfig cfg = configService.current();
        ZonedDateTime syncDate = ZonedDateTime.ofInstant(startedAt, cfg.timezone());
        int attempts = 0;
        Throwable lastCause = null;
        FailureCategory lastCategory = FailureCategory.UNKNOWN;
        boolean remoteEverTouched = false;
        int fileCount = 0;

        try {
            while (attempts < cfg.maxAttempts()) {
                attempts++;
                try {
                    // Open / corruption-check on every attempt so a previous attempt's
                    // corruption signal is healed before the next try.
                    gitService.initOrOpenLocalClone();

                    StatsSnapshot snapshot = statsReader.read(cfg.statsPath());
                    Map<UUID, byte[]> files = snapshot.files();
                    fileCount = files.size();
                    metrics.setDetectedStatsFiles(fileCount);

                    gitService.fetchAndResetToRemote();
                    remoteEverTouched = true;

                    // US2 (T037) will insert snapshot writing here when
                    // cfg.snapshotsEnabled() is true and no directory for today exists.

                    gitService.writeFiles(files);

                    if (!gitService.hasChanges()) {
                        return terminalSuccess(
                                SyncOutcome.SUCCESS_NO_CHANGES, trigger, startedAt,
                                fileCount, null, attempts, cfg);
                    }

                    String message = "Update stats for " + cfg.serverName() + " - "
                            + syncDate.format(COMMIT_TIMESTAMP);
                    String sha = gitService.commitAndPush(
                            cfg.commitAuthorName(), cfg.commitAuthorEmail(), message);
                    return terminalSuccess(
                            SyncOutcome.SUCCESS_WITH_COMMIT, trigger, startedAt,
                            fileCount, sha, attempts, cfg);

                } catch (GitOperationException ex) {
                    lastCause = ex;
                    lastCategory = ex.category();
                    if (!isTransient(lastCategory)) {
                        logger.log(Level.WARNING, ABANDON_LOG_MESSAGE, ex);
                        return terminalFailure(trigger, startedAt, fileCount, attempts,
                                lastCategory, ex, remoteEverTouched);
                    }
                    if (attempts < cfg.maxAttempts()) {
                        if (!backoff(attempts, cfg.initialBackoff())) {
                            return terminalFailure(trigger, startedAt, fileCount, attempts,
                                    lastCategory, ex, remoteEverTouched);
                        }
                    }
                } catch (IOException ex) {
                    lastCause = ex;
                    lastCategory = FailureCategory.IO;
                    if (attempts < cfg.maxAttempts()) {
                        if (!backoff(attempts, cfg.initialBackoff())) {
                            return terminalFailure(trigger, startedAt, fileCount, attempts,
                                    lastCategory, ex, remoteEverTouched);
                        }
                    }
                } catch (RuntimeException ex) {
                    // Unexpected — treat as UNKNOWN and abandon immediately (plugin bug).
                    lastCause = ex;
                    lastCategory = FailureCategory.UNKNOWN;
                    logger.log(Level.WARNING, ABANDON_LOG_MESSAGE, ex);
                    return terminalFailure(trigger, startedAt, fileCount, attempts,
                            lastCategory, ex, remoteEverTouched);
                }
            }

            // Retry budget exhausted on a transient category.
            if (lastCause != null) {
                logger.log(Level.WARNING, "sync exhausted "
                        + cfg.maxAttempts() + " attempts on " + lastCategory, lastCause);
            }
            return terminalFailure(trigger, startedAt, fileCount, attempts,
                    lastCategory, lastCause, remoteEverTouched);

        } finally {
            lock.release();
        }
    }

    // ---------------------------------------------------------------- transitions

    private SyncOutcome terminalSuccess(
            SyncOutcome outcome,
            Trigger trigger,
            Instant startedAt,
            int files,
            String commitSha,
            int attempts,
            TickstatsSyncConfig cfg) {
        long durationMs = durationMs(startedAt);
        metrics.setLastOutcome(outcome);
        metrics.setLastTrigger(trigger);
        metrics.setLastAttempts(attempts);
        metrics.setLastFailureCategory(null);
        metrics.setLastReachability(SyncMetrics.Reachability.OK);
        if (outcome == SyncOutcome.SUCCESS_WITH_COMMIT) {
            metrics.setLastSuccessAt(clock.instant());
        } else {
            // SUCCESS_NO_CHANGES is a successful cycle — also updates lastSuccessAt.
            metrics.setLastSuccessAt(clock.instant());
        }
        emitLogLine(outcome, trigger, files, commitSha, durationMs, null, attempts);
        return outcome;
    }

    private SyncOutcome terminalFailure(
            Trigger trigger,
            Instant startedAt,
            int files,
            int attempts,
            FailureCategory category,
            Throwable cause,
            boolean remoteEverTouched) {
        long durationMs = durationMs(startedAt);
        metrics.setLastOutcome(SyncOutcome.FAILURE);
        metrics.setLastTrigger(trigger);
        metrics.setLastAttempts(attempts);
        metrics.setLastFailureCategory(category);
        updateReachabilityOnFailure(category, remoteEverTouched);
        emitLogLine(SyncOutcome.FAILURE, trigger, files, null, durationMs, category, attempts);
        if (cause != null) {
            logger.log(Level.FINE, "failure cause", cause);
        }
        return SyncOutcome.FAILURE;
    }

    private void updateReachabilityOnFailure(FailureCategory category, boolean remoteEverTouched) {
        switch (category) {
            case AUTH, NETWORK -> metrics.setLastReachability(SyncMetrics.Reachability.FAILED);
            case CONFLICT -> metrics.setLastReachability(SyncMetrics.Reachability.OK);
            case IO -> {
                if (remoteEverTouched) {
                    metrics.setLastReachability(SyncMetrics.Reachability.FAILED);
                }
                // else leave unchanged
            }
            case UNKNOWN -> {
                if (remoteEverTouched) {
                    metrics.setLastReachability(SyncMetrics.Reachability.FAILED);
                }
            }
        }
    }

    private long durationMs(Instant startedAt) {
        return Duration.between(startedAt, clock.instant()).toMillis();
    }

    private boolean backoff(int attempt, Duration initial) {
        // attempt is 1-based (we just finished attempt N, sleep before attempt N+1).
        long shift = Math.min(30L, (long) (attempt - 1));
        Duration delay = initial.multipliedBy(1L << shift);
        try {
            sleeper.sleep(delay);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isTransient(FailureCategory category) {
        return switch (category) {
            case NETWORK, CONFLICT, IO -> true;
            case AUTH, UNKNOWN -> false;
        };
    }

    private void emitLogLine(
            SyncOutcome outcome,
            Trigger trigger,
            int files,
            String commitSha,
            long durationMs,
            FailureCategory category,
            int attempts) {
        String line = "outcome=" + outcome
                + " trigger=" + trigger
                + " files=" + files
                + " commit=" + (commitSha == null ? "none" : commitSha)
                + " duration_ms=" + durationMs
                + " category=" + (category == null ? "none" : category)
                + " attempts=" + attempts;
        if (outcome == SyncOutcome.FAILURE) {
            logger.warning(line);
        } else {
            logger.info(line);
        }
    }

    /**
     * Exposed for US2 (T037) and internal helpers: computes the local-calendar date
     * for today in the configured timezone at the given instant.
     */
    public LocalDate localDateAt(Instant instant) {
        return ZonedDateTime.ofInstant(instant, configService.current().timezone()).toLocalDate();
    }
}
