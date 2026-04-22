package com.skycryck.tickstatssync.git;

import com.skycryck.tickstatssync.config.TickstatsSyncConfig;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public final class GitService {

    private static final String NOT_IMPLEMENTED =
            "TickstatsSync service not yet implemented - Foundational-phase stub, implemented in user story US1";

    @SuppressWarnings("unused")
    private final TickstatsSyncConfig config;

    @SuppressWarnings("unused")
    private final Path workdir;

    public GitService(TickstatsSyncConfig config, Path workdir) {
        this.config = config;
        this.workdir = workdir;
    }

    public void initOrOpenLocalClone() {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    public void fetchAndResetToRemote() {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    public void writeFiles(Map<UUID, byte[]> data) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    public boolean hasChanges() {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    public String commitAndPush(String author, String email, String message) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    public void writeSnapshot(LocalDate today, Map<UUID, byte[]> data) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }
}
