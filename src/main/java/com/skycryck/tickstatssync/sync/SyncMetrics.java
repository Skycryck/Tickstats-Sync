package com.skycryck.tickstatssync.sync;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class SyncMetrics {

    public enum Reachability {
        OK,
        FAILED,
        UNKNOWN
    }

    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();
    private final AtomicReference<Instant> nextScheduledAt = new AtomicReference<>();
    private final AtomicInteger detectedStatsFiles = new AtomicInteger(0);
    private final AtomicReference<Reachability> lastReachability =
            new AtomicReference<>(Reachability.UNKNOWN);
    private final AtomicReference<SyncOutcome> lastOutcome = new AtomicReference<>();
    private final AtomicReference<FailureCategory> lastFailureCategory = new AtomicReference<>();
    private final AtomicInteger lastAttempts = new AtomicInteger(0);
    private final AtomicReference<SyncOrchestrator.Trigger> lastTrigger = new AtomicReference<>();
    private final AtomicReference<String> configInvalidReason = new AtomicReference<>();
    private final AtomicReference<String> lastCommitSha = new AtomicReference<>();
    private final AtomicLong lastDurationMs = new AtomicLong(0L);

    public Instant lastSuccessAt() {
        return lastSuccessAt.get();
    }

    public void setLastSuccessAt(Instant instant) {
        lastSuccessAt.set(instant);
    }

    public Instant nextScheduledAt() {
        return nextScheduledAt.get();
    }

    public void setNextScheduledAt(Instant instant) {
        nextScheduledAt.set(instant);
    }

    public int detectedStatsFiles() {
        return detectedStatsFiles.get();
    }

    public void setDetectedStatsFiles(int count) {
        detectedStatsFiles.set(count);
    }

    public Reachability lastReachability() {
        return lastReachability.get();
    }

    public void setLastReachability(Reachability value) {
        lastReachability.set(value);
    }

    public SyncOutcome lastOutcome() {
        return lastOutcome.get();
    }

    public void setLastOutcome(SyncOutcome outcome) {
        lastOutcome.set(outcome);
    }

    public FailureCategory lastFailureCategory() {
        return lastFailureCategory.get();
    }

    public void setLastFailureCategory(FailureCategory category) {
        lastFailureCategory.set(category);
    }

    public int lastAttempts() {
        return lastAttempts.get();
    }

    public void setLastAttempts(int value) {
        lastAttempts.set(value);
    }

    public SyncOrchestrator.Trigger lastTrigger() {
        return lastTrigger.get();
    }

    public void setLastTrigger(SyncOrchestrator.Trigger trigger) {
        lastTrigger.set(trigger);
    }

    public String configInvalidReason() {
        return configInvalidReason.get();
    }

    public void setConfigInvalidReason(String reason) {
        configInvalidReason.set(reason);
    }

    public String lastCommitSha() {
        return lastCommitSha.get();
    }

    public void setLastCommitSha(String sha) {
        lastCommitSha.set(sha);
    }

    public long lastDurationMs() {
        return lastDurationMs.get();
    }

    public void setLastDurationMs(long durationMs) {
        lastDurationMs.set(durationMs);
    }
}
