package com.skycryck.tickstatssync.stats;

import java.nio.file.Path;

public final class StatsReader {

    private static final String NOT_IMPLEMENTED =
            "TickstatsSync service not yet implemented - Foundational-phase stub, implemented in user story US1";

    public StatsSnapshot read(Path statsDir) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }
}
