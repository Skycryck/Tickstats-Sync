package com.skycryck.tickstatssync.sync;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class SyncLock {

    private final AtomicReference<Instant> heldSince = new AtomicReference<>(null);
    private final Clock clock;

    public SyncLock(Clock clock) {
        this.clock = clock;
    }

    public boolean tryAcquire() {
        return heldSince.compareAndSet(null, clock.instant());
    }

    public void release() {
        heldSince.set(null);
    }

    public Optional<Instant> heldSince() {
        return Optional.ofNullable(heldSince.get());
    }
}
