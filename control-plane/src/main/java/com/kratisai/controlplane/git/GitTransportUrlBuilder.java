package com.kratisai.controlplane.git;

import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.RepositoryType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GitTransportUrlBuilder {

    private static final Pattern HTTP_URL_PATTERN =
            Pattern.compile("^(https?://)([^/?#]+)([^?#]*)(\\?[^#]*)?(#.*)?$", Pattern.CASE_INSENSITIVE);

    public String buildAuthenticatedCloneUrl(String rawUrl, RepositoryType type, GitAuthMaterial auth) {
        Objects.requireNonNull(rawUrl, "rawUrl must not be null");

        if (auth == null || (auth.maybeToken().isEmpty() && auth.maybeSshKey().isEmpty())) {
            return rawUrl;
        }

        if (auth.maybeSshKey().isPresent()) {
            return rawUrl;
        }

        String token = auth.maybeToken().orElse("");
        if (token.isEmpty()) {
            return rawUrl;
        }

        Matcher matcher = HTTP_URL_PATTERN.matcher(rawUrl);
        if (!matcher.matches()) {
            return rawUrl;
        }

        String scheme = matcher.group(1);
        String hostPart = matcher.group(2);
        String path = matcher.group(3);
        String query = matcher.group(4);
        String fragment = matcher.group(5);

        if (hostPart.contains("@")) {
            throw new IllegalArgumentException("Repository URL must not contain user info: " + rawUrl);
        }

        String encodedToken = URLEncoder.encode(token.trim(), StandardCharsets.UTF_8);
        String username = getUsernameConvention(type);
        String userInfo = username + encodedToken;

        StringBuilder result = new StringBuilder();
        result.append(scheme).append(userInfo).append("@").append(hostPart).append(path);
        if (query != null) {
            result.append(query);
        }
        if (fragment != null) {
            result.append(fragment);
        }
        return result.toString();
    }

    private String getUsernameConvention(RepositoryType type) {
        if (type == null) {
            return "oauth2:";
        }
        return switch (type) {
            case GITHUB, GITLAB -> "oauth2:";
            case BITBUCKET -> "x-token-auth:";
            case AZURE -> "";
            case GENERIC -> "";
        };
    }
}
