package com.skycryck.tickstatssync.stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class StatsReader {

    /** Lowercase UUID v4 shape followed by .json — case-sensitive per spec. */
    static final Pattern UUID_FILENAME =
            Pattern.compile("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.json$");

    private static final int READ_RETRY_ATTEMPTS = 3;
    private static final long READ_RETRY_SLEEP_MS = 50L;

    private final Clock clock;
    private final Logger logger;

    public StatsReader(Clock clock, Logger logger) {
        this.clock = clock;
        this.logger = logger;
    }

    /**
     * Reads every valid UUID-named {@code <uuid>.json} under {@code statsDir} as raw
     * bytes. Non-UUID filenames are skipped (logged at FINE). A missing or empty
     * directory yields an empty snapshot with a single INFO log line (spec § Edge Cases).
     */
    public StatsSnapshot read(Path statsDir) throws IOException {
        if (statsDir == null || !Files.isDirectory(statsDir)) {
            logger.info("stats directory not present or not a directory: " + statsDir
                    + " (returning empty snapshot)");
            return new StatsSnapshot(Map.of(), clock.instant());
        }

        Map<UUID, byte[]> files = new HashMap<>();
        try (Stream<Path> stream = Files.list(statsDir)) {
            Iterable<Path> iterable = stream::iterator;
            for (Path file : iterable) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String name = file.getFileName().toString();
                java.util.regex.Matcher m = UUID_FILENAME.matcher(name);
                if (!m.matches()) {
                    logger.log(Level.FINE, "Skipping non-UUID stats file: {0}", name);
                    continue;
                }
                UUID uuid = UUID.fromString(m.group(1));
                byte[] bytes = readWithRetry(file);
                files.put(uuid, bytes);
            }
        }

        if (files.isEmpty()) {
            logger.info("stats directory contains no UUID-named files: " + statsDir);
        }
        return new StatsSnapshot(files, clock.instant());
    }

    private byte[] readWithRetry(Path file) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= READ_RETRY_ATTEMPTS; attempt++) {
            try {
                return Files.readAllBytes(file);
            } catch (IOException ex) {
                last = ex;
                if (attempt < READ_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(READ_RETRY_SLEEP_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while retrying read of " + file, ie);
                    }
                }
            }
        }
        throw new IOException("failed to read " + file + " after "
                + READ_RETRY_ATTEMPTS + " attempts", last);
    }
}
