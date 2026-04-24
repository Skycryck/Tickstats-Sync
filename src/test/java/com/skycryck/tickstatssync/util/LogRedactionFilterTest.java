package com.skycryck.tickstatssync.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

final class LogRedactionFilterTest {

    private static final String TOKEN = "ghp_SECRETSECRETSECRETSECRETSECRETSECRETSE";

    @Test
    void maskReplacesTokenInMessage() {
        PatMasker masker = new PatMasker();
        masker.setToken(TOKEN);
        LogRedactionFilter filter = new LogRedactionFilter(masker);

        LogRecord record = new LogRecord(Level.INFO, "auth failed for token " + TOKEN);
        filter.isLoggable(record);

        assertThat(record.getMessage()).doesNotContain(TOKEN).contains("***");
    }

    @Test
    void maskReplacesTokenInFormattedArguments() {
        PatMasker masker = new PatMasker();
        masker.setToken(TOKEN);
        LogRedactionFilter filter = new LogRedactionFilter(masker);

        LogRecord record = new LogRecord(Level.WARNING, "token = {0}");
        record.setParameters(new Object[] {TOKEN});
        filter.isLoggable(record);

        assertThat(record.getMessage()).doesNotContain(TOKEN).contains("***");
        assertThat(record.getParameters()).isNull();
    }

    @Test
    void maskReplacesTokenInThrowableMessage() {
        PatMasker masker = new PatMasker();
        masker.setToken(TOKEN);
        LogRedactionFilter filter = new LogRedactionFilter(masker);

        RuntimeException cause = new RuntimeException("bad token " + TOKEN);
        RuntimeException wrapper = new RuntimeException("wrap " + TOKEN, cause);
        LogRecord record = new LogRecord(Level.SEVERE, "failure");
        record.setThrown(wrapper);
        filter.isLoggable(record);

        Throwable masked = record.getThrown();
        assertThat(masked.getMessage()).doesNotContain(TOKEN);
        assertThat(masked.getCause()).isNotNull();
        assertThat(masked.getCause().getMessage()).doesNotContain(TOKEN);
    }

    @Test
    void filterReturnsTrueSoRecordStillLogs() {
        PatMasker masker = new PatMasker();
        masker.setToken(TOKEN);
        LogRedactionFilter filter = new LogRedactionFilter(masker);

        LogRecord record = new LogRecord(Level.INFO, "harmless");
        assertThat(filter.isLoggable(record)).isTrue();
    }
}
