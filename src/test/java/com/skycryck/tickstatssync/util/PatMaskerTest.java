package com.skycryck.tickstatssync.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class PatMaskerTest {

    private static final String TOKEN_A = "ghp_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String TOKEN_B = "github_pat_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    @Test
    void maskIsNoOpBeforeSetToken() {
        PatMasker masker = new PatMasker();
        assertThat(masker.mask("leaks " + TOKEN_A + " here")).contains(TOKEN_A);
    }

    @Test
    void nullInputReturnsNull() {
        PatMasker masker = new PatMasker();
        masker.setToken(TOKEN_A);
        assertThat(masker.mask(null)).isNull();
    }

    @Test
    void emptyOrNullTokenIsNoOp() {
        PatMasker masker = new PatMasker();
        masker.setToken("");
        assertThat(masker.mask("any text " + TOKEN_A)).contains(TOKEN_A);
        masker.setToken(null);
        assertThat(masker.mask("any text " + TOKEN_A)).contains(TOKEN_A);
    }

    @Test
    void tokenNeverSurvivesMaskAcrossRandomizedSubstrings() {
        PatMasker masker = new PatMasker();
        masker.setToken(TOKEN_A);
        Random rng = new Random(1337L);
        for (int i = 0; i < 500; i++) {
            String prefix = randomText(rng);
            String suffix = randomText(rng);
            String payload = prefix + TOKEN_A + suffix;
            String masked = masker.mask(payload);
            assertThat(masked).doesNotContain(TOKEN_A).contains("***");
        }
    }

    @Test
    void rotationSwapsRedactedValueWithNoLeakageWindow() {
        PatMasker masker = new PatMasker();
        masker.setToken(TOKEN_A);
        assertThat(masker.mask("use " + TOKEN_A)).doesNotContain(TOKEN_A);

        masker.setToken(TOKEN_B);
        assertThat(masker.mask("use " + TOKEN_B)).doesNotContain(TOKEN_B);
        assertThat(masker.mask("still mentions " + TOKEN_A)).contains(TOKEN_A);
    }

    private String randomText(Random rng) {
        int len = rng.nextInt(40);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + rng.nextInt(26)));
        }
        return sb.toString();
    }
}
