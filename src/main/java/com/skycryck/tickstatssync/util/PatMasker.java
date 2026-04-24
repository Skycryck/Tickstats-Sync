package com.skycryck.tickstatssync.util;

public class PatMasker {

    private volatile String currentToken;

    public void setToken(String token) {
        this.currentToken = token;
    }

    public String mask(String input) {
        if (input == null) {
            return null;
        }
        String token = this.currentToken;
        if (token == null || token.isEmpty()) {
            return input;
        }
        return input.replace(token, "***");
    }
}
