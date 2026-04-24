package com.skycryck.tickstatssync.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;

public record TickstatsSyncConfig(
        String ownerAndRepo,
        String branch,
        String token,
        String commitAuthorName,
        String commitAuthorEmail,
        String serverName,
        Path statsPath,
        String cronExpression,
        ZoneId timezone,
        boolean syncOnStartup,
        boolean snapshotsEnabled,
        int maxAttempts,
        Duration initialBackoff) {

    @Override
    public String toString() {
        return "TickstatsSyncConfig{"
                + "ownerAndRepo=" + ownerAndRepo
                + ", branch=" + branch
                + ", token=***"
                + ", commitAuthorName=" + commitAuthorName
                + ", commitAuthorEmail=" + commitAuthorEmail
                + ", serverName=" + serverName
                + ", statsPath=" + statsPath
                + ", cronExpression=" + cronExpression
                + ", timezone=" + timezone
                + ", syncOnStartup=" + syncOnStartup
                + ", snapshotsEnabled=" + snapshotsEnabled
                + ", maxAttempts=" + maxAttempts
                + ", initialBackoff=" + initialBackoff
                + '}';
    }
}
