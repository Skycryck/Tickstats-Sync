package com.skycryck.tickstatssync.config;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigService {

    private static final Pattern OWNER_REPO_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$");
    private static final Pattern SERVER_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(".+@.+");
    private static final Pattern BRANCH_CONTROL_PATTERN = Pattern.compile("\\s|[\\x00-\\x1f]");

    private final Supplier<FileConfiguration> configSupplier;
    private final Path serverDirectory;
    private final Supplier<String> envTokenSupplier;
    private final AtomicReference<TickstatsSyncConfig> active = new AtomicReference<>();

    public ConfigService(Supplier<FileConfiguration> configSupplier, Path serverDirectory) {
        this(configSupplier, serverDirectory, () -> System.getenv("TICKSTATSSYNC_GITHUB_TOKEN"));
    }

    public ConfigService(
            Supplier<FileConfiguration> configSupplier,
            Path serverDirectory,
            Supplier<String> envTokenSupplier) {
        this.configSupplier = configSupplier;
        this.serverDirectory = serverDirectory;
        this.envTokenSupplier = envTokenSupplier;
    }

    public TickstatsSyncConfig load() {
        FileConfiguration raw = configSupplier.get();

        String ownerAndRepo = requireString(raw, "github.repo");
        if (!OWNER_REPO_PATTERN.matcher(ownerAndRepo).matches()) {
            throw configError("github.repo is not in \"owner/repo\" form: " + ownerAndRepo);
        }

        String branch = raw.getString("github.branch", "main");
        if (branch == null || branch.isEmpty()) {
            throw configError("github.branch must not be empty");
        }
        if (BRANCH_CONTROL_PATTERN.matcher(branch).find()) {
            throw configError("github.branch contains whitespace or control characters: " + branch);
        }

        String configuredToken = raw.getString("github.token", "");
        String resolvedToken = (configuredToken != null && !configuredToken.isEmpty())
                ? configuredToken
                : envTokenSupplier.get();
        if (resolvedToken == null || resolvedToken.isEmpty()) {
            throw configError("no GitHub token supplied - set github.token or export TICKSTATSSYNC_GITHUB_TOKEN");
        }

        String commitAuthorName = requireString(raw, "github.commit-author-name");
        String commitAuthorEmail = requireString(raw, "github.commit-author-email");
        if (!EMAIL_PATTERN.matcher(commitAuthorEmail).matches()) {
            throw configError("github.commit-author-email is not a valid email shape: " + commitAuthorEmail);
        }

        String serverName = requireString(raw, "server.name");
        if (!SERVER_NAME_PATTERN.matcher(serverName).matches()) {
            throw configError("server.name is not path-segment-safe: " + serverName);
        }

        // Paper 26.x stores vanilla stats at world/players/stats (legacy Paper / older
        // Bukkit forks used world/stats). Operators with a non-default layout override
        // this key explicitly in config.yml.
        String statsPathRaw = raw.getString("server.stats-path", "world/players/stats");
        Path statsPath = serverDirectory.resolve(statsPathRaw).toAbsolutePath().normalize();
        if (!Files.isDirectory(statsPath)) {
            throw configError("server.stats-path is not a readable directory: " + statsPath);
        }

        String cronExpression = raw.getString("sync.cron", "0 */6 * * *");
        if (cronExpression == null || cronExpression.isEmpty()) {
            throw configError("sync.cron is required");
        }
        try {
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
                    .parse(cronExpression);
        } catch (IllegalArgumentException ex) {
            throw configError("sync.cron is not a valid Unix cron expression: " + ex.getMessage());
        }

        String timezoneRaw = raw.getString("sync.timezone", "Europe/Paris");
        ZoneId timezone;
        try {
            timezone = ZoneId.of(timezoneRaw);
        } catch (DateTimeException ex) {
            throw configError("sync.timezone \"" + timezoneRaw + "\" is not a recognized IANA zone");
        }

        boolean syncOnStartup = raw.getBoolean("sync.sync-on-startup", false);
        boolean snapshotsEnabled = raw.getBoolean("sync.snapshots-enabled", true);

        int maxAttempts = raw.getInt("retry.max-attempts", 3);
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw configError("retry.max-attempts must be in [1, 10]: " + maxAttempts);
        }

        int backoffSeconds = raw.getInt("retry.initial-backoff-seconds", 10);
        if (backoffSeconds < 1 || backoffSeconds > 300) {
            throw configError("retry.initial-backoff-seconds must be in [1, 300]: " + backoffSeconds);
        }
        Duration initialBackoff = Duration.ofSeconds(backoffSeconds);

        TickstatsSyncConfig config = new TickstatsSyncConfig(
                ownerAndRepo,
                branch,
                resolvedToken,
                commitAuthorName,
                commitAuthorEmail,
                serverName,
                statsPath,
                cronExpression,
                timezone,
                syncOnStartup,
                snapshotsEnabled,
                maxAttempts,
                initialBackoff);

        active.set(config);
        return config;
    }

    public TickstatsSyncConfig current() {
        return active.get();
    }

    public void swap(TickstatsSyncConfig next) {
        active.set(next);
    }

    private static String requireString(FileConfiguration raw, String key) {
        String value = raw.getString(key);
        if (value == null || value.isEmpty()) {
            throw configError(key + " is missing");
        }
        return value;
    }

    private static ConfigValidationException configError(String detail) {
        return new ConfigValidationException("config error: " + detail);
    }

    public static final class ConfigValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConfigValidationException(String message) {
            super(message);
        }
    }
}
