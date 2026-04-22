package com.skycryck.tickstatssync.stats;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record StatsSnapshot(Map<UUID, byte[]> files, Instant readAt) {

    public StatsSnapshot {
        if (files == null) {
            throw new IllegalArgumentException("files must not be null");
        }
        Map<UUID, byte[]> defensive = new HashMap<>(files.size());
        for (Map.Entry<UUID, byte[]> entry : files.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("snapshot entries must be non-null");
            }
            defensive.put(entry.getKey(), entry.getValue().clone());
        }
        files = Collections.unmodifiableMap(defensive);
    }
}
