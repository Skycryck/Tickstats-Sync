package com.skycryck.tickstatssync.util;

import java.util.logging.Filter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

public final class LogRedactionFilter implements Filter {

    private final PatMasker masker;
    private final Formatter messageFormatter = new SimpleFormatter();

    public LogRedactionFilter(PatMasker masker) {
        this.masker = masker;
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        String rawMessage = record.getMessage();
        if (rawMessage != null) {
            String formatted = formatMessage(record, rawMessage);
            record.setMessage(masker.mask(formatted));
            record.setParameters(null);
        }

        Throwable thrown = record.getThrown();
        if (thrown != null) {
            record.setThrown(maskThrowable(thrown));
        }

        return true;
    }

    private String formatMessage(LogRecord record, String raw) {
        Object[] params = record.getParameters();
        if (params == null || params.length == 0) {
            return raw;
        }
        try {
            return java.text.MessageFormat.format(raw, params);
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    private Throwable maskThrowable(Throwable source) {
        Throwable current = source;
        Throwable maskedHead = null;
        Throwable maskedTail = null;
        while (current != null) {
            String maskedMessage = masker.mask(current.getMessage());
            Throwable copy = new MaskedThrowable(current.getClass().getName(), maskedMessage);
            copy.setStackTrace(current.getStackTrace());
            if (maskedHead == null) {
                maskedHead = copy;
                maskedTail = copy;
            } else {
                try {
                    maskedTail.initCause(copy);
                } catch (IllegalStateException ignored) {
                }
                maskedTail = copy;
            }
            current = current.getCause();
        }
        return maskedHead != null ? maskedHead : source;
    }

    @SuppressWarnings("unused")
    private Formatter formatter() {
        return messageFormatter;
    }

    static final class MaskedThrowable extends Throwable {
        private static final long serialVersionUID = 1L;
        private final String originalClassName;

        MaskedThrowable(String originalClassName, String maskedMessage) {
            super(maskedMessage);
            this.originalClassName = originalClassName;
        }

        @Override
        public String toString() {
            String msg = getLocalizedMessage();
            return msg == null ? originalClassName : originalClassName + ": " + msg;
        }
    }
}
