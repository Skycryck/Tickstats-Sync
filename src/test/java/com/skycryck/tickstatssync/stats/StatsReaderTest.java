package com.skycryck.tickstatssync.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StatsReaderTest {

    private static final UUID U1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID U2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID U3 = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private final Logger logger = Logger.getLogger(StatsReaderTest.class.getName());
    private final Clock clock = Clock.fixed(Instant.parse("2026-04-22T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void readsExactlyTheThreeUuidFilesFromFixture(@TempDir Path tmp) throws IOException {
        copyFixtureTo(tmp);

        StatsSnapshot snapshot = new StatsReader(clock, logger).read(tmp);

        assertThat(snapshot.files().keySet()).containsExactlyInAnyOrder(U1, U2, U3);
    }

    @Test
    void bytesAreByteForByteEqualToSource(@TempDir Path tmp) throws IOException {
        copyFixtureTo(tmp);

        StatsSnapshot snapshot = new StatsReader(clock, logger).read(tmp);

        byte[] expected = Files.readAllBytes(tmp.resolve(U1 + ".json"));
        assertThat(snapshot.files().get(U1)).isEqualTo(expected);
    }

    @Test
    void nonUuidFilenamesAreDropped(@TempDir Path tmp) throws IOException {
        copyFixtureTo(tmp);
        assertThat(Files.exists(tmp.resolve("not-a-uuid.json"))).isTrue();

        StatsSnapshot snapshot = new StatsReader(clock, logger).read(tmp);

        assertThat(snapshot.files()).hasSize(3);
        Set<String> asNames = snapshot.files().keySet().stream()
                .map(UUID::toString).collect(java.util.stream.Collectors.toSet());
        assertThat(asNames).doesNotContain("not-a-uuid");
    }

    @Test
    void emptyDirectoryReturnsEmptySnapshot(@TempDir Path tmp) throws IOException {
        StatsSnapshot snapshot = new StatsReader(clock, logger).read(tmp);
        assertThat(snapshot.files()).isEmpty();
        assertThat(snapshot.readAt()).isEqualTo(Instant.parse("2026-04-22T12:00:00Z"));
    }

    @Test
    void missingDirectoryReturnsEmptySnapshot(@TempDir Path tmp) throws IOException {
        StatsSnapshot snapshot = new StatsReader(clock, logger).read(tmp.resolve("does-not-exist"));
        assertThat(snapshot.files()).isEmpty();
    }

    @Test
    void uppercaseHexUuidFilenameIsDropped(@TempDir Path tmp) throws IOException {
        // Use a UUID with hex letters so toUpperCase actually changes the string.
        String upper = "ABCDEF00-0000-4000-8000-000000000000.json";
        Files.writeString(tmp.resolve(upper), "{}");

        StatsSnapshot snapshot = new StatsReader(clock, logger).read(tmp);
        assertThat(snapshot.files()).isEmpty();
    }

    private void copyFixtureTo(Path target) throws IOException {
        for (String name : new String[] {
                U1 + ".json", U2 + ".json", U3 + ".json", "not-a-uuid.json"}) {
            try (InputStream in = getClass().getResourceAsStream("/stats/sample-stats/" + name)) {
                if (in == null) {
                    throw new IllegalStateException("fixture file missing: " + name);
                }
                Files.copy(in, target.resolve(name));
            }
        }
    }
}
