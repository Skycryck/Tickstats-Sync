package com.skycryck.tickstatssync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigServiceTest {

    @Test
    void validMinimalFixtureLoadsWithDefaults(@TempDir Path serverDir) throws IOException {
        Path statsDir = Files.createDirectory(serverDir.resolve("world-stats"));
        YamlConfiguration raw = loadFixture("valid-minimal.yml", statsDir);

        ConfigService service = new ConfigService(() -> raw, serverDir, () -> null);
        TickstatsSyncConfig config = service.load();

        assertThat(config.ownerAndRepo()).isEqualTo("owner/repo-name");
        assertThat(config.branch()).isEqualTo("main");
        assertThat(config.token()).isEqualTo("ghp_minimalPlaceholderTokenValue1234567890");
        assertThat(config.timezone()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(config.syncOnStartup()).isFalse();
        assertThat(config.snapshotsEnabled()).isTrue();
        assertThat(config.maxAttempts()).isEqualTo(3);
        assertThat(config.initialBackoff()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void validFullFixtureLoadsAllFields(@TempDir Path serverDir) throws IOException {
        Path statsDir = Files.createDirectory(serverDir.resolve("world-stats"));
        YamlConfiguration raw = loadFixture("valid-full.yml", statsDir);

        ConfigService service = new ConfigService(() -> raw, serverDir, () -> null);
        TickstatsSyncConfig config = service.load();

        assertThat(config.ownerAndRepo()).isEqualTo("skycryck/tickstats-data");
        assertThat(config.branch()).isEqualTo("main");
        assertThat(config.cronExpression()).isEqualTo("0 8,14,22 * * *");
        assertThat(config.timezone()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(config.snapshotsEnabled()).isTrue();
        assertThat(config.maxAttempts()).isEqualTo(3);
        assertThat(config.initialBackoff()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void missingRepoFails(@TempDir Path serverDir) throws IOException {
        Path statsDir = Files.createDirectory(serverDir.resolve("world-stats"));
        YamlConfiguration raw = loadFixture("invalid-missing-repo.yml", statsDir);

        ConfigService service = new ConfigService(() -> raw, serverDir, () -> null);
        assertThatThrownBy(service::load)
                .isInstanceOf(ConfigService.ConfigValidationException.class)
                .hasMessageContaining("github.repo is missing");
    }

    @Test
    void badCronFails(@TempDir Path serverDir) throws IOException {
        Path statsDir = Files.createDirectory(serverDir.resolve("world-stats"));
        YamlConfiguration raw = loadFixture("invalid-bad-cron.yml", statsDir);

        ConfigService service = new ConfigService(() -> raw, serverDir, () -> null);
        assertThatThrownBy(service::load)
                .isInstanceOf(ConfigService.ConfigValidationException.class)
                .hasMessageContaining("sync.cron is not a valid Unix cron expression");
    }

    @Test
    void emptyTokenWithEnvFallbackResolves(@TempDir Path serverDir) throws IOException {
        Path statsDir = Files.createDirectory(serverDir.resolve("world-stats"));
        YamlConfiguration raw = loadFixture("valid-minimal.yml", statsDir);
        raw.set("github.token", "");

        ConfigService service =
                new ConfigService(() -> raw, serverDir, () -> "ghp_fromEnv00000000000000000000000000000000");
        TickstatsSyncConfig config = service.load();

        assertThat(config.token()).isEqualTo("ghp_fromEnv00000000000000000000000000000000");
    }

    @Test
    void noTokenAnywhereFails(@TempDir Path serverDir) throws IOException {
        Path statsDir = Files.createDirectory(serverDir.resolve("world-stats"));
        YamlConfiguration raw = loadFixture("valid-minimal.yml", statsDir);
        raw.set("github.token", "");

        ConfigService service = new ConfigService(() -> raw, serverDir, () -> null);
        assertThatThrownBy(service::load)
                .hasMessageContaining("no GitHub token supplied");
    }

    private YamlConfiguration loadFixture(String fixtureName, Path statsDir) throws IOException {
        String yaml;
        try (InputStream in = getClass().getResourceAsStream("/config/" + fixtureName)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: " + fixtureName);
            }
            yaml = new String(in.readAllBytes());
        }
        String substituted = yaml.replace("@STATS_PATH@", statsDir.toString().replace("\\", "/"));
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(substituted);
        } catch (Exception ex) {
            throw new IOException("failed to parse fixture " + fixtureName, ex);
        }
        return config;
    }
}
