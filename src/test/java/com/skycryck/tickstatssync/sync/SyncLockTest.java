package com.skycryck.tickstatssync.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class SyncLockTest {

    @Test
    void acquireSucceedsOnceAndRejectsWhileHeld() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-22T12:00:00Z"));
        SyncLock lock = new SyncLock(clock);

        assertThat(lock.tryAcquire()).isTrue();
        assertThat(lock.tryAcquire()).isFalse();
    }

    @Test
    void releaseClearsHolder() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-22T12:00:00Z"));
        SyncLock lock = new SyncLock(clock);

        lock.tryAcquire();
        lock.release();
        assertThat(lock.heldSince()).isEmpty();
        assertThat(lock.tryAcquire()).isTrue();
    }

    @Test
    void heldSinceReportsAcquireInstant() {
        Instant acquireAt = Instant.parse("2026-04-22T12:00:00Z");
        MutableClock clock = new MutableClock(acquireAt);
        SyncLock lock = new SyncLock(clock);

        lock.tryAcquire();
        assertThat(lock.heldSince()).contains(acquireAt);
    }

    @Test
    void concurrentAcquireRejectionFR003a() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-22T12:00:00Z"));
        SyncLock lock = new SyncLock(clock);

        lock.tryAcquire();
        clock.advanceSeconds(30);

        assertThat(lock.tryAcquire()).isFalse();
        assertThat(lock.heldSince()).hasValueSatisfying(held ->
                assertThat(held).isEqualTo(Instant.parse("2026-04-22T12:00:00Z")));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
