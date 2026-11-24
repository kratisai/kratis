package com.kratisai.controlplane.ingestion.teamwiki;

import java.util.Optional;
import java.util.Set;

/** Resolves the path after {@code /wiki/repo/} into a repository name and optional page slug. */
public final class TeamWikiLinkResolver {

    private TeamWikiLinkResolver() {}

    public record RepoTarget(String repoName, String pageSlug) {}

    /**
     * Repository names may themselves contain slashes, so the longest known name that matches the whole
     * path or a slash-aligned prefix wins; anything else is treated as that repository's page slug.
     */
    public static Optional<RepoTarget> resolve(String repoPath, Set<String> repoNames) {
        String longest = null;
        for (String candidate : repoNames) {
            if (candidate == null || candidate.isEmpty()) continue;
            boolean exact = repoPath.equals(candidate);
            boolean prefixed = repoPath.length() > candidate.length()
                    && repoPath.startsWith(candidate)
                    && repoPath.charAt(candidate.length()) == '/';
            if ((exact || prefixed) && (longest == null || candidate.length() > longest.length())) {
                longest = candidate;
            }
        }
        if (longest == null) return Optional.empty();
        if (repoPath.length() == longest.length()) return Optional.of(new RepoTarget(longest, null));
        String pageSlug = repoPath.substring(longest.length() + 1);
        return Optional.of(new RepoTarget(longest, pageSlug.isEmpty() ? null : pageSlug));
    }
}
