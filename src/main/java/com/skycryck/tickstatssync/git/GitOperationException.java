package com.skycryck.tickstatssync.git;

import com.skycryck.tickstatssync.sync.FailureCategory;

public final class GitOperationException extends Exception {

    private static final long serialVersionUID = 1L;

    private final FailureCategory category;

    public GitOperationException(FailureCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public GitOperationException(FailureCategory category, String message) {
        super(message);
        this.category = category;
    }

    public FailureCategory category() {
        return category;
    }
}
