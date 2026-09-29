package com.kratisai.controlplane.git.credential;

import java.util.Optional;

public record GitAuthMaterial(String tokenValue, String sshKeyValue) {

    public GitAuthMaterial {
        if (tokenValue != null && sshKeyValue != null) {
            throw new IllegalArgumentException("GitAuthMaterial cannot have both a token and an SSH key");
        }
    }

    public static GitAuthMaterial ofToken(String token) {
        return new GitAuthMaterial(token, null);
    }

    public static GitAuthMaterial ofSshKey(String pem) {
        return new GitAuthMaterial(null, pem);
    }

    public static GitAuthMaterial none() {
        return new GitAuthMaterial(null, null);
    }

    public Optional<String> maybeToken() {
        return Optional.ofNullable(tokenValue);
    }

    public Optional<String> maybeSshKey() {
        return Optional.ofNullable(sshKeyValue);
    }
}
